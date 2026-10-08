package io.github.an_dr_eas_k.ncphotowidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class PhotoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.update(context)
        if (!WidgetUpdater.hasImage(context) && Settings(context).isConfigured) {
            Scheduler.enqueue(context, PhotoWorker.ACTION_NEXT)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            PhotoWorker.ACTION_NEXT, PhotoWorker.ACTION_PREV -> Scheduler.enqueue(context, intent.action!!)
        }
    }

    override fun onEnabled(context: Context) = Scheduler.apply(context)

    override fun onDisabled(context: Context) = Scheduler.apply(context)
}
