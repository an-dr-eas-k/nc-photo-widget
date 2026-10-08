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
        if (!settings.isConfigured) {
            startActivity(Intent(this, MainActivity::class.java))
        } else if (fileId != null) {
            val uri = Uri.parse("${settings.serverUrl}/f/$fileId")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(NEXTCLOUD_PACKAGE))
            } catch (e: ActivityNotFoundException) {
                // Nextcloud app not installed: fall back to the browser.
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
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
