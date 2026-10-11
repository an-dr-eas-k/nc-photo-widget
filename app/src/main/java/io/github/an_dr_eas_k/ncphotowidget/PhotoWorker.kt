package io.github.an_dr_eas_k.ncphotowidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.workDataOf
import java.io.IOException
import java.util.concurrent.TimeUnit

class PhotoWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val settings = Settings(ctx)
        if (!settings.isConfigured || settings.folders.isEmpty()) {
            WidgetUpdater.update(ctx)
            return Result.success()
        }

        val action = inputData.getString(KEY_ACTION) ?: ACTION_NEXT
        val client = NextcloudClient(settings)
        if (action == ACTION_REFRESH) return scan(client, settings)

        val backup = PhotoState.backup(ctx)
        try {
            repeat(3) {
                val id = if (action == ACTION_PREV) {
                    PhotoState.previous(ctx)
                } else {
                    // Never wait for a scan here: it runs as its own job, and known photos are used meanwhile.
                    if (PhotoState.needsRefresh(ctx, settings.listRefreshHours)) Scheduler.scan(ctx, restart = false)
                    val ids = PhotoState.knownIds(ctx)
                    if (ids.isEmpty()) {
                        // Before the first photo is found, the scan itself puts it on the widget.
                        if (PhotoState.hasScanned(ctx)) WidgetUpdater.update(ctx, ctx.getString(R.string.widget_empty))
                        return Result.success()
                    }
                    PhotoState.next(ctx, ids)
                }
                if (id == null) {
                    WidgetUpdater.update(ctx, ctx.getString(R.string.widget_empty))
                    return Result.success()
                }
                try {
                    load(client, id)
                    WidgetUpdater.update(ctx, loading = moreQueued())
                    fillBuffer(client)
                    return Result.success()
                } catch (e: HttpException) {
                    if (e.code != 404) throw e
                    PhotoState.dropCurrent(ctx)
                }
            }
        } catch (e: Exception) {
            // Keep showing the old picture; the next scheduled run tries again.
            PhotoState.restore(ctx, backup)
        }
        WidgetUpdater.update(ctx, loading = moreQueued())
        return Result.success()
    }

    /** Rescans the folders; shows a photo early only when there was no list to pick from. */
    private fun scan(client: NextcloudClient, settings: Settings): Result {
        val ctx = applicationContext
        val wasEmpty = !PhotoState.hasList(ctx)
        var shownEarly = false
        val showEarly: (Long) -> Unit = { first ->
            try {
                load(client, first)
                PhotoState.show(ctx, first)
                WidgetUpdater.update(ctx)
                shownEarly = true
            } catch (e: IOException) {
                // The scan continues; a later tap or tick retries.
            }
        }
        var lastProgress = 0L
        val onProgress: (Int, Int) -> Unit = { folders, photos ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastProgress >= PROGRESS_INTERVAL_MS) {
                lastProgress = now
                setProgressAsync(workDataOf(PROGRESS_FOLDERS to folders, PROGRESS_PHOTOS to photos))
            }
        }
        try {
            val ids = PhotoState.refreshList(
                ctx, client, settings.folders, if (wasEmpty) showEarly else null, onProgress, { isStopped },
            )
            if (wasEmpty && !shownEarly) {
                if (ids.isEmpty()) WidgetUpdater.update(ctx, ctx.getString(R.string.widget_empty))
                else Scheduler.enqueue(ctx, ACTION_NEXT)
            }
            fillBuffer(client)
        } catch (e: Exception) {
            // Recorded for the settings screen; the previous list stays in use.
            if (wasEmpty && !shownEarly) WidgetUpdater.update(ctx)
        }
        return Result.success()
    }

    /** Further taps are waiting behind this run, so the widget should keep showing that it is busy. */
    private fun moreQueued(): Boolean = try {
        WorkManager.getInstance(applicationContext).getWorkInfosForUniqueWork(Scheduler.ON_DEMAND).get()
            .any { it.id != id && (it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED) }
    } catch (e: Exception) {
        false
    }

    /** Makes [id] the widget image, from the buffer when possible. */
    private fun load(client: NextcloudClient, id: Long) {
        val ctx = applicationContext
        if (WidgetUpdater.showBuffered(ctx, id)) return
        WidgetUpdater.showLoading(ctx)
        val bytes = client.fetchPreview(id)
        WidgetUpdater.buffer(ctx, id, bytes)
        WidgetUpdater.saveImage(ctx, bytes)
    }

    /** Keeps the pictures on both sides of the current one downloaded, so taps switch without waiting. */
    private fun fillBuffer(client: NextcloudClient) {
        val ctx = applicationContext
        val (previous, current, next) = PhotoState.neighbors(ctx)
        for (id in listOfNotNull(previous, next)) {
            if (WidgetUpdater.isBuffered(ctx, id)) continue
            try {
                WidgetUpdater.buffer(ctx, id, client.fetchPreview(id))
            } catch (e: Exception) {
                // Not buffered: that tap downloads its picture instead.
            }
        }
        WidgetUpdater.trimBuffer(ctx, setOfNotNull(previous, current, next))
    }

    companion object {
        const val KEY_ACTION = "action"
        const val ACTION_NEXT = "io.github.an_dr_eas_k.ncphotowidget.NEXT"
        const val ACTION_PREV = "io.github.an_dr_eas_k.ncphotowidget.PREV"
        /** Rescans the folders regardless of the list age. */
        const val ACTION_REFRESH = "io.github.an_dr_eas_k.ncphotowidget.REFRESH"
        const val PROGRESS_FOLDERS = "folders"
        const val PROGRESS_PHOTOS = "photos"
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}

object Scheduler {
    private const val PERIODIC = "cycle"
    const val ON_DEMAND = "on-demand"
    const val SCAN = "scan"
    private const val KEEP_ALIVE = "keep-alive"

    /** WorkManager cannot repeat faster than this, so shorter intervals use an alarm chain. */
    private const val MIN_PERIODIC_MINUTES = 15
    const val ACTION_TICK = "io.github.an_dr_eas_k.ncphotowidget.TICK"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun request(action: String) = OneTimeWorkRequestBuilder<PhotoWorker>()
        .setConstraints(network)
        .setInputData(workDataOf(PhotoWorker.KEY_ACTION to action))
        .addTag(action)
        .build()

    fun enqueue(c: Context, action: String) {
        if (action == PhotoWorker.ACTION_REFRESH) return scan(c, restart = true)
        keepAlive(c)
        WorkManager.getInstance(c).enqueueUniqueWork(ON_DEMAND, ExistingWorkPolicy.APPEND_OR_REPLACE, request(action))
    }

    /** Scans run apart from taps, so a long scan never holds up the next picture. */
    fun scan(c: Context, restart: Boolean) {
        keepAlive(c)
        // Restarting also stops a scan of folders that may no longer be selected.
        val policy = if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(c).enqueueUniqueWork(SCAN, policy, request(PhotoWorker.ACTION_REFRESH))
    }

    fun usesAlarm(c: Context) = Settings(c).intervalMinutes < MIN_PERIODIC_MINUTES

    // WorkManager toggles a manifest component whenever no work is pending; that package change
    // makes the launcher rebuild the widget, which flickers. A job that never comes due prevents it.
    private fun keepAlive(c: Context) {
        val request = OneTimeWorkRequestBuilder<PhotoWorker>()
            .setInitialDelay(3650, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(KEEP_ALIVE, ExistingWorkPolicy.KEEP, request)
    }

    /** Runs the cycle only while at least one widget exists. */
    fun apply(c: Context) {
        val wm = WorkManager.getInstance(c)
        val settings = Settings(c)
        cancelAlarm(c)
        if (!settings.isConfigured || !WidgetUpdater.hasWidgets(c)) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(KEEP_ALIVE)
            return
        }
        keepAlive(c)
        if (usesAlarm(c)) {
            wm.cancelUniqueWork(PERIODIC)
            scheduleAlarm(c, settings.intervalMinutes)
            return
        }
        val minutes = settings.intervalMinutes.toLong()
        val request = PeriodicWorkRequestBuilder<PhotoWorker>(minutes, TimeUnit.MINUTES)
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .setConstraints(network)
            .setInputData(workDataOf(PhotoWorker.KEY_ACTION to PhotoWorker.ACTION_NEXT))
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Handles one alarm: shows the next picture and arms the following alarm. */
    fun onTick(c: Context) {
        val settings = Settings(c)
        if (!usesAlarm(c) || !settings.isConfigured || !WidgetUpdater.hasWidgets(c)) return
        enqueue(c, PhotoWorker.ACTION_NEXT)
        scheduleAlarm(c, settings.intervalMinutes)
    }

    // Non-wakeup and inexact: the phone is not woken just to change a picture, and Doze may delay it.
    private fun scheduleAlarm(c: Context, minutes: Int) {
        c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + minutes * 60_000L,
            alarmIntent(c),
        )
    }

    private fun cancelAlarm(c: Context) = c.getSystemService(AlarmManager::class.java).cancel(alarmIntent(c))

    private fun alarmIntent(c: Context): PendingIntent = WidgetUpdater.broadcast(c, ACTION_TICK, 4)
}
