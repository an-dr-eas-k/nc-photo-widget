package io.github.an_dr_eas_k.ncphotowidget

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.Constraints
import java.io.IOException
import java.util.concurrent.TimeUnit

class PhotoWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val settings = Settings(ctx)
        if (!settings.isConfigured) {
            WidgetUpdater.update(ctx)
            return Result.success()
        }

        val action = inputData.getString(KEY_ACTION) ?: ACTION_NEXT
        val client = NextcloudClient(settings)
        val backup = PhotoState.backup(ctx)
        var shownEarly = false
        try {
            repeat(3) {
                val id = if (action == ACTION_PREV) {
                    PhotoState.previous(ctx)
                } else {
                    val ids = PhotoState.ids(ctx, client, settings.folders, settings.listRefreshHours) { first ->
                        // First scan: show a picture right away instead of waiting for the full list.
                        try {
                            WidgetUpdater.saveImage(ctx, client.fetchPreview(first))
                            PhotoState.show(ctx, first)
                            WidgetUpdater.update(ctx)
                            shownEarly = true
                        } catch (e: IOException) {
                            // The scan continues; the regular pick below retries.
                        }
                    }
                    if (shownEarly) return Result.success()
                    PhotoState.next(ctx, ids)
                }
                if (id == null) {
                    WidgetUpdater.update(ctx, ctx.getString(R.string.widget_empty))
                    return Result.success()
                }
                try {
                    WidgetUpdater.saveImage(ctx, client.fetchPreview(id))
                    WidgetUpdater.update(ctx)
                    return Result.success()
                } catch (e: HttpException) {
                    if (e.code != 404) throw e
                    PhotoState.dropCurrent(ctx)
                }
            }
        } catch (e: IOException) {
            // Keep showing the old picture; the next scheduled run tries again.
            if (!shownEarly) PhotoState.restore(ctx, backup)
        }
        return Result.success()
    }

    companion object {
        const val KEY_ACTION = "action"
        const val ACTION_NEXT = "io.github.an_dr_eas_k.ncphotowidget.NEXT"
        const val ACTION_PREV = "io.github.an_dr_eas_k.ncphotowidget.PREV"
    }
}

object Scheduler {
    private const val PERIODIC = "cycle"
    private const val ON_DEMAND = "on-demand"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueue(c: Context, action: String) {
        val request = OneTimeWorkRequestBuilder<PhotoWorker>()
            .setConstraints(network)
            .setInputData(Data.Builder().putString(PhotoWorker.KEY_ACTION, action).build())
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(ON_DEMAND, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Runs the periodic cycle only while at least one widget exists. */
    fun apply(c: Context) {
        val wm = WorkManager.getInstance(c)
        val settings = Settings(c)
        if (!settings.isConfigured || !WidgetUpdater.hasWidgets(c)) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val minutes = settings.intervalMinutes.coerceAtLeast(15).toLong()
        val request = PeriodicWorkRequestBuilder<PhotoWorker>(minutes, TimeUnit.MINUTES)
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .setConstraints(network)
            .setInputData(Data.Builder().putString(PhotoWorker.KEY_ACTION, PhotoWorker.ACTION_NEXT).build())
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
