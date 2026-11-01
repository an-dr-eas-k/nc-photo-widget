package io.github.an_dr_eas_k.ncphotowidget

import android.app.Activity
import android.os.Bundle

/**
 * Installing from Android Studio force-stops the app, which cancels the widgets' tap handlers and
 * sends no update. Starting the app from here redraws the widgets and so restores them.
 */
class DebugLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WidgetConfig.migrateLegacy(this)
        WidgetUpdater.updateAll(this)
        Scheduler.apply(this)
        finish()
    }
}
