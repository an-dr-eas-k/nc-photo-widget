package io.github.an_dr_eas_k.ncphotowidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.work.Data
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
        val backup = PhotoState.backup(ctx)
        var shownEarly = false
        val showEarly: (Long) -> Unit = { first ->
            // Scanning: show a picture right away instead of waiting for the full list.
            try {
                load(client, first)
                PhotoState.show(ctx, first)
                WidgetUpdater.update(ctx)
                shownEarly = true
            } catch (e: IOException) {
                // The scan continues; the regular pick below retries.
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
        var forceRefresh = action == ACTION_REFRESH
        try {
            repeat(3) {
                val id = if (action == ACTION_PREV) {
                    PhotoState.previous(ctx)
                } else {
                    val ids = if (forceRefresh) {
                        PhotoState.refreshList(ctx, client, settings.folders, showEarly, onProgress, { isStopped })
                    } else {
                        PhotoState.ids(
                            ctx, client, settings.folders, settings.listRefreshHours, showEarly, onProgress, { isStopped },
                        )
                    }
                    forceRefresh = false
                    if (shownEarly) {
                        fillBuffer(client)
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
            if (!shownEarly) PhotoState.restore(ctx, backup)
        }
        WidgetUpdater.update(ctx, loading = moreQueued())
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
        /** Rescans the folders regardless of the list age, then shows a picture from the new list. */
        const val ACTION_REFRESH = "io.github.an_dr_eas_k.ncphotowidget.REFRESH"
        const val PROGRESS_FOLDERS = "folders"
        const val PROGRESS_PHOTOS = "photos"
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}

object Scheduler {
    private const val PERIODIC = "cycle"
    const val ON_DEMAND = "on-demand"
    private const val KEEP_ALIVE = "keep-alive"

    /** WorkManager cannot repeat faster than this, so shorter intervals use an alarm chain. */
    private const val MIN_PERIODIC_MINUTES = 15
    const val ACTION_TICK = "io.github.an_dr_eas_k.ncphotowidget.TICK"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueue(c: Context, action: String) {
        keepAlive(c)
        val request = OneTimeWorkRequestBuilder<PhotoWorker>()
            .setConstraints(network)
            .setInputData(Data.Builder().putString(PhotoWorker.KEY_ACTION, action).build())
            .addTag(action)
            .build()
        // A rescan supersedes queued taps and stops a scan of folders that may no longer be selected.
        val policy = if (action == PhotoWorker.ACTION_REFRESH) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
        WorkManager.getInstance(c).enqueueUniqueWork(ON_DEMAND, policy, request)
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
            .setInputData(Data.Builder().putString(PhotoWorker.KEY_ACTION, PhotoWorker.ACTION_NEXT).build())
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Handles one alarm: shows the next picture and arms the following alarm. */
    fun onTick(c: Context) {
        if (!usesAlarm(c) || !Settings(c).isConfigured || !WidgetUpdater.hasWidgets(c)) return
        enqueue(c, PhotoWorker.ACTION_NEXT)
        scheduleAlarm(c, Settings(c).intervalMinutes)
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

    private fun alarmIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 4,
        Intent(c, PhotoWidgetProvider::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
