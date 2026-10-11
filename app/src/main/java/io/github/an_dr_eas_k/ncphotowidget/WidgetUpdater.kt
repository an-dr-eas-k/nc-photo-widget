package io.github.an_dr_eas_k.ncphotowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import java.io.File

object WidgetUpdater {
    private fun imageFile(c: Context) = File(c.filesDir, "current.jpg")
    private fun bufferDir(c: Context) = File(c.filesDir, "buffer")
    private fun bufferFile(c: Context, id: Long) = File(bufferDir(c).apply { mkdirs() }, "$id.jpg")

    private fun widgetIds(c: Context): IntArray =
        AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, PhotoWidgetProvider::class.java))

    fun hasWidgets(c: Context) = widgetIds(c).isNotEmpty()

    fun hasImage(c: Context) = imageFile(c).exists()

    fun saveImage(c: Context, bytes: ByteArray) = writeAtomically(imageFile(c), bytes)

    fun isBuffered(c: Context, id: Long) = bufferFile(c, id).exists()

    fun buffer(c: Context, id: Long, bytes: ByteArray) = writeAtomically(bufferFile(c, id), bytes)

    /** Makes the buffered picture of [id] the current one; false when it is not buffered. */
    fun showBuffered(c: Context, id: Long): Boolean {
        val file = bufferFile(c, id)
        if (!file.exists()) return false
        saveImage(c, file.readBytes())
        return true
    }

    fun trimBuffer(c: Context, keep: Set<Long>) {
        bufferDir(c).listFiles()?.forEach {
            val id = it.nameWithoutExtension.toLongOrNull()
            if (id == null || id !in keep) it.delete()
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(target)
    }

    /** Redraws every widget from the stored image; a [message] is shown on top when given. */
    fun update(c: Context, message: String? = null, loading: Boolean = false) {
        val ids = widgetIds(c)
        if (ids.isEmpty()) return
        val bitmap = if (hasImage(c)) BitmapFactory.decodeFile(imageFile(c).path) else null
        val settings = Settings(c)
        val placeholder = if (settings.isConfigured && settings.folders.isNotEmpty()) R.string.widget_loading else R.string.widget_setup
        val views = buildViews(c, bitmap, message ?: if (bitmap == null) c.getString(placeholder) else null)
        views.setViewVisibility(R.id.loading, if (loading) View.VISIBLE else View.GONE)
        AppWidgetManager.getInstance(c).updateAppWidget(ids, views)
    }

    private fun buildViews(c: Context, bitmap: Bitmap?, message: String?): RemoteViews {
        val views = RemoteViews(c.packageName, R.layout.widget_photo)
        if (bitmap != null) views.setImageViewBitmap(R.id.image, bitmap)
        views.setTextViewText(R.id.message, message ?: "")
        views.setViewVisibility(R.id.message, if (message == null) View.GONE else View.VISIBLE)

        views.setOnClickPendingIntent(R.id.zone_left, broadcast(c, PhotoWorker.ACTION_PREV, 1))
        views.setOnClickPendingIntent(R.id.zone_right, broadcast(c, PhotoWorker.ACTION_NEXT, 2))
        views.setOnClickPendingIntent(
            R.id.zone_center,
            PendingIntent.getActivity(
                c, 3, Intent(c, OpenPhotoActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return views
    }

    private fun broadcast(c: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c, requestCode,
            Intent(c, PhotoWidgetProvider::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
