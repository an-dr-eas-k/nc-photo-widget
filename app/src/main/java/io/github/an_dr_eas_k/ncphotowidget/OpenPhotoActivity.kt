package io.github.an_dr_eas_k.ncphotowidget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Invisible trampoline: opens the current picture in the official Nextcloud app. */
class OpenPhotoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fileId = PhotoState.current(this)
        val settings = Settings(this)
        if (!settings.isConfigured || settings.folders.isEmpty() || fileId == null) {
            // Nothing to open yet, so the tap leads to the settings with the sync status instead.
            startActivity(Intent(this, MainActivity::class.java))
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
