package io.github.an_dr_eas_k.ncphotowidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Invisible trampoline: opens the current picture in the official Nextcloud app. */
class OpenPhotoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val fileId = PhotoState(this, widgetId).current()
        val settings = Settings(this)
        if (!settings.isConfigured || WidgetConfig(this, widgetId).folders.isEmpty() || fileId == null) {
            // Nothing to open yet, so the tap leads to the widget's settings with the sync status instead.
            startActivity(
                Intent(this, MainActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse("${settings.serverUrl}/f/$fileId"))
            // Falls back to the browser when the Nextcloud app is not installed.
            for (intent in listOf(Intent(view).setPackage(NEXTCLOUD_PACKAGE), view)) {
                try {
                    startActivity(intent)
                    break
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
        finish()
    }

    private companion object {
        const val NEXTCLOUD_PACKAGE = "com.nextcloud.client"
    }
}
