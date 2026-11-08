package io.github.an_dr_eas_k.ncphotowidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class PhotoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetConfig.migrateLegacy(context)
        for (id in appWidgetIds) {
            WidgetUpdater.update(context, id)
            if (!WidgetUpdater.hasImage(context, id) && Settings(context).isConfigured) {
                Scheduler.enqueue(context, id, PhotoWorker.ACTION_NEXT)
            }
        }
    }

    // The picture is cut to the widget's size, so it is redrawn when the widget is resized.
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        WidgetUpdater.update(context, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            Scheduler.cancel(context, id)
            WidgetConfig.delete(context, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            PhotoWorker.ACTION_NEXT, PhotoWorker.ACTION_PREV -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                // Immediate feedback; the worker clears it once the picture is shown.
                WidgetUpdater.showLoading(context, id)
                Scheduler.enqueue(context, id, intent.action!!)
            }
            PhotoWorker.ACTION_INFO -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                val state = PhotoState(context, id)
                // A second tap hides the date again. The day is in the photo list, so this needs no network.
                val day = if (state.info() == null) state.shown()?.let { state.dayOf(it) } else null
                state.setInfo(day?.let { LocalDate.ofEpochDay(it.toLong()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) })
                WidgetUpdater.update(context, id)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                WidgetConfig.migrateLegacy(context)
                WidgetUpdater.updateAll(context)
                Scheduler.apply(context)
            }
        }
    }

    override fun onEnabled(context: Context) = Scheduler.apply(context)

    override fun onDisabled(context: Context) = Scheduler.apply(context)
}
