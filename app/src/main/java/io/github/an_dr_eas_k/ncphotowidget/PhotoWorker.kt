package io.github.an_dr_eas_k.ncphotowidget

import android.appwidget.AppWidgetManager
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
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit

class PhotoWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    @Volatile
    private var client: NextcloudClient? = null

    // A newer tap replaces this job: its running download is dropped, not waited for.
    override fun onStopped() {
        client?.cancel()
    }

    override fun doWork(): Result {
        val ctx = applicationContext
        WidgetConfig.migrateLegacy(ctx)
        val widgetId = inputData.getInt(KEY_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (!WidgetUpdater.exists(ctx, widgetId)) return Result.success()
        val settings = Settings(ctx)
        val config = WidgetConfig(ctx, widgetId)
        val state = PhotoState(ctx, widgetId)
        if (!settings.isConfigured || config.folders.isEmpty()) {
            WidgetUpdater.update(ctx, widgetId)
            return Result.success()
        }

        val action = inputData.getString(KEY_ACTION) ?: ACTION_NEXT
        val client = NextcloudClient(settings).also { this.client = it }
        if (action == ACTION_REFRESH) return scan(widgetId, client, config, state)
        if (action == ACTION_INFO) return showInfo(widgetId, client, state)

        val backup = state.backup()
        var retrying = false
        try {
            repeat(3) {
                val unfinished = if (action == ACTION_PREV) null else state.pending()
                val id = if (unfinished != null) {
                    // The last download never finished (e.g. the server hung): the tap restarts it.
                    retrying = true
                    unfinished
                } else if (action == ACTION_PREV) {
                    state.previous()
                } else {
                    // Never wait for a scan here: it runs as its own job, and known photos are used meanwhile.
                    if (state.needsRefresh(config.listRefreshHours)) Scheduler.scan(ctx, widgetId, restart = false)
                    val ids = state.knownIds()
                    if (ids.isEmpty()) {
                        // Before the first photo is found, the scan itself puts it on the widget.
                        if (state.hasScanned()) WidgetUpdater.update(ctx, widgetId, ctx.getString(R.string.widget_empty))
                        return Result.success()
                    }
                    state.next(ids)
                }
                if (id == null) {
                    WidgetUpdater.update(ctx, widgetId, ctx.getString(R.string.widget_empty))
                    return Result.success()
                }
                try {
                    load(widgetId, client, state, id)
                    WidgetUpdater.update(ctx, widgetId, loading = moreQueued(widgetId))
                    fillBuffer(widgetId, client, state)
                    return Result.success()
                } catch (e: HttpException) {
                    if (e.code != 404) throw e
                    state.dropCurrent()
                }
            }
        } catch (e: Exception) {
            // A replaced job leaves the widget to its successor.
            if (isStopped) return Result.success()
            // Keep showing the old picture. A photo that fails twice is given up, so the next tap moves on.
            if (retrying) state.dropCurrent() else state.restore(backup)
            state.setFailed(true)
        }
        WidgetUpdater.update(ctx, widgetId, loading = moreQueued(widgetId))
        return Result.success()
    }

    /** Looks up when the shown photo was taken and puts it on the widget. */
    private fun showInfo(widgetId: Int, client: NextcloudClient, state: PhotoState): Result {
        val ctx = applicationContext
        try {
            val id = state.shown()
            val time = if (id == null) null else client.fetchDateTime(id)
            state.setInfo(time?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)))
        } catch (e: Exception) {
            // The widget just stays as it was; the tap can be repeated.
        }
        if (!isStopped) WidgetUpdater.update(ctx, widgetId)
        return Result.success()
    }

    /** Rescans the folders; shows a photo early only when there was no list to pick from. */
    private fun scan(widgetId: Int, client: NextcloudClient, config: WidgetConfig, state: PhotoState): Result {
        val ctx = applicationContext
        val wasEmpty = !state.hasList()
        var shownEarly = false
        val showEarly: (Long) -> Unit = { first ->
            try {
                load(widgetId, client, state, first)
                state.show(first)
                WidgetUpdater.update(ctx, widgetId)
                shownEarly = true
            } catch (e: IOException) {
                // The scan continues; a later tap or tick retries.
            }
        }
        // The settings screen shows how long this scan has been running and how many photos it found.
        val scanStart = System.currentTimeMillis()
        setProgressAsync(workDataOf(PROGRESS_START to scanStart, PROGRESS_PHOTOS to 0))
        var lastProgress = 0L
        val onProgress: (Int, Int) -> Unit = { _, photos ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastProgress >= PROGRESS_INTERVAL_MS) {
                lastProgress = now
                setProgressAsync(workDataOf(PROGRESS_START to scanStart, PROGRESS_PHOTOS to photos))
            }
        }
        try {
            val ids = state.refreshList(
                client, config.folders, config.tags, if (wasEmpty) showEarly else null, onProgress, { isStopped },
            )
            if (wasEmpty && !shownEarly) {
                if (ids.isEmpty()) WidgetUpdater.update(ctx, widgetId, ctx.getString(R.string.widget_empty))
                else Scheduler.enqueue(ctx, widgetId, ACTION_NEXT)
            }
            fillBuffer(widgetId, client, state)
        } catch (e: Exception) {
            // Recorded for the settings screen; the previous list stays in use.
            if (wasEmpty && !shownEarly) WidgetUpdater.update(ctx, widgetId)
        }
        return Result.success()
    }

    /** Further taps are waiting behind this run, so the widget should keep showing that it is busy. */
    private fun moreQueued(widgetId: Int): Boolean = try {
        WorkManager.getInstance(applicationContext).getWorkInfosForUniqueWork(Scheduler.onDemandName(widgetId)).get()
            .any { it.id != id && (it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED) }
    } catch (e: Exception) {
        false
    }

    /** Makes [id] the widget image, from the buffer when possible. */
    private fun load(widgetId: Int, client: NextcloudClient, state: PhotoState, id: Long) {
        val ctx = applicationContext
        if (!WidgetUpdater.showBuffered(ctx, widgetId, id)) {
            WidgetUpdater.showLoading(ctx, widgetId)
            val bytes = client.fetchPreview(id)
            WidgetUpdater.buffer(ctx, widgetId, id, bytes)
            WidgetUpdater.saveImage(ctx, widgetId, bytes)
        }
        state.markShown(id, state.isPreFiltered(id))
    }

    /**
     * Keeps the picture of every photo in the history, so walking back and forth needs no network,
     * plus the next one, so the next tap switches without waiting. The oldest ones go as the history slides.
     */
    private fun fillBuffer(widgetId: Int, client: NextcloudClient, state: PhotoState) {
        val ctx = applicationContext
        val next = state.neighbors().third
        if (next != null && !WidgetUpdater.isBuffered(ctx, widgetId, next) && !isStopped) {
            try {
                WidgetUpdater.buffer(ctx, widgetId, next, client.fetchPreview(next))
            } catch (e: Exception) {
                // Not buffered: that tap downloads its picture instead.
            }
        }
        if (!isStopped) WidgetUpdater.trimBuffer(ctx, widgetId, state.historyIds().toSet() + listOfNotNull(next))
    }

    companion object {
        const val KEY_ACTION = "action"
        const val KEY_WIDGET_ID = "widget_id"
        const val ACTION_NEXT = "io.github.an_dr_eas_k.ncphotowidget.NEXT"
        const val ACTION_PREV = "io.github.an_dr_eas_k.ncphotowidget.PREV"
        /** Shows when the shown photo was taken. */
        const val ACTION_INFO = "io.github.an_dr_eas_k.ncphotowidget.INFO"
        /** Rescans the folders regardless of the list age. */
        const val ACTION_REFRESH = "io.github.an_dr_eas_k.ncphotowidget.REFRESH"
        /** Progress of a running scan: when it started, in epoch milliseconds. */
        const val PROGRESS_START = "scan_start"
        const val PROGRESS_PHOTOS = "photos"
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}

object Scheduler {
    private const val KEEP_ALIVE = "keep-alive"

    fun onDemandName(widgetId: Int) = "on-demand-$widgetId"
    fun scanName(widgetId: Int) = "scan-$widgetId"
    private fun cycleName(widgetId: Int) = "cycle-$widgetId"

    /** WorkManager cannot repeat faster than this; shorter intervals saved by version 1.0 are raised to it. */
    private const val MIN_PERIODIC_MINUTES = 15

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    // Taps wait for no network: offline they fail at once, instead of leaving the spinner up.
    private fun request(widgetId: Int, action: String) = OneTimeWorkRequestBuilder<PhotoWorker>()
        .apply { if (action == PhotoWorker.ACTION_REFRESH) setConstraints(network) }
        .setInputData(workDataOf(PhotoWorker.KEY_ACTION to action, PhotoWorker.KEY_WIDGET_ID to widgetId))
        .addTag(action)
        .build()

    fun enqueue(c: Context, widgetId: Int, action: String) {
        if (action == PhotoWorker.ACTION_REFRESH) return scan(c, widgetId, restart = true)
        keepAlive(c)
        WorkManager.getInstance(c)
            .enqueueUniqueWork(onDemandName(widgetId), ExistingWorkPolicy.REPLACE, request(widgetId, action))
    }

    /** Scans run apart from taps, so a long scan never holds up the next picture. */
    fun scan(c: Context, widgetId: Int, restart: Boolean) {
        keepAlive(c)
        // Restarting also stops a scan of folders that may no longer be selected.
        val policy = if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(c).enqueueUniqueWork(scanName(widgetId), policy, request(widgetId, PhotoWorker.ACTION_REFRESH))
    }

    // WorkManager toggles a manifest component whenever no work is pending; that package change
    // makes the launcher rebuild the widget, which flickers. A job that never comes due prevents it.
    private fun keepAlive(c: Context) {
        val request = OneTimeWorkRequestBuilder<PhotoWorker>()
            .setInitialDelay(3650, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(KEEP_ALIVE, ExistingWorkPolicy.KEEP, request)
    }

    /** Stops everything a deleted widget had scheduled. */
    fun cancel(c: Context, widgetId: Int) {
        val wm = WorkManager.getInstance(c)
        wm.cancelUniqueWork(cycleName(widgetId))
        wm.cancelUniqueWork(onDemandName(widgetId))
        wm.cancelUniqueWork(scanName(widgetId))
    }

    /** Runs each widget's cycle, and only while at least one widget exists. */
    fun apply(c: Context) {
        val wm = WorkManager.getInstance(c)
        val ids = WidgetUpdater.widgetIds(c)
        if (!Settings(c).isConfigured || ids.isEmpty()) {
            ids.forEach { wm.cancelUniqueWork(cycleName(it)) }
            wm.cancelUniqueWork(KEEP_ALIVE)
            return
        }
        keepAlive(c)
        for (id in ids) {
            val interval = WidgetConfig(c, id).intervalMinutes
            if (interval == Settings.INTERVAL_MANUAL) {
                wm.cancelUniqueWork(cycleName(id))
                continue
            }
            val minutes = interval.coerceAtLeast(MIN_PERIODIC_MINUTES).toLong()
            val request = PeriodicWorkRequestBuilder<PhotoWorker>(minutes, TimeUnit.MINUTES)
                .setInitialDelay(minutes, TimeUnit.MINUTES)
                .setConstraints(network)
                .setInputData(workDataOf(PhotoWorker.KEY_ACTION to PhotoWorker.ACTION_NEXT, PhotoWorker.KEY_WIDGET_ID to id))
                .build()
            wm.enqueueUniquePeriodicWork(cycleName(id), ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
