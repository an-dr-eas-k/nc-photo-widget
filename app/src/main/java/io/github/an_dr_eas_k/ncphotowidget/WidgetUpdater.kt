package io.github.an_dr_eas_k.ncphotowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.io.File

object WidgetUpdater {
    private fun imageFile(c: Context, id: Int) = File(WidgetFiles.dir(c, id), "current.jpg")
    private fun bufferDir(c: Context, id: Int) = File(WidgetFiles.dir(c, id), "buffer")
    private fun bufferFile(c: Context, widgetId: Int, id: Long) = File(bufferDir(c, widgetId).apply { mkdirs() }, "$id.jpg")

    fun widgetIds(c: Context): IntArray = WidgetConfig.widgetIds(c)

    fun hasWidgets(c: Context) = widgetIds(c).isNotEmpty()

    fun exists(c: Context, widgetId: Int) = widgetId in widgetIds(c)

    fun hasImage(c: Context, widgetId: Int) = imageFile(c, widgetId).exists()

    fun saveImage(c: Context, widgetId: Int, bytes: ByteArray) = writeAtomically(imageFile(c, widgetId), bytes)

    fun isBuffered(c: Context, widgetId: Int, id: Long) = bufferFile(c, widgetId, id).exists()

    fun buffer(c: Context, widgetId: Int, id: Long, bytes: ByteArray) = writeAtomically(bufferFile(c, widgetId, id), bytes)

    /** Makes the buffered picture of [id] the current one; false when it is not buffered. */
    fun showBuffered(c: Context, widgetId: Int, id: Long): Boolean {
        val file = bufferFile(c, widgetId, id)
        if (!file.exists()) return false
        saveImage(c, widgetId, file.readBytes())
        return true
    }

    fun trimBuffer(c: Context, widgetId: Int, keep: Set<Long>) {
        bufferDir(c, widgetId).listFiles()?.forEach {
            val id = it.nameWithoutExtension.toLongOrNull()
            if (id == null || id !in keep) it.delete()
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(target)
    }

    /** Redraws every widget. */
    fun updateAll(c: Context) = widgetIds(c).forEach { update(c, it) }

    /** Redraws one widget from its stored image; a [message] is shown on top when given. */
    fun update(c: Context, widgetId: Int, message: String? = null, loading: Boolean = false) {
        if (!exists(c, widgetId)) return
        val bitmap = if (hasImage(c, widgetId)) BitmapFactory.decodeFile(imageFile(c, widgetId).path) else null
        val settings = Settings(c)
        val config = WidgetConfig(c, widgetId)
        val placeholder = if (settings.isConfigured && config.folders.isNotEmpty()) R.string.widget_loading else R.string.widget_setup
        val text = message ?: if (bitmap == null) c.getString(placeholder) else null

        val manager = AppWidgetManager.getInstance(c)
        val state = PhotoState(c, widgetId)
        val filter = if (state.shownIsPlain()) PhotoFilter.NONE else config.filter
        val size = sizeDp(c, manager.getAppWidgetOptions(widgetId))
        val image = shaped(c, bitmap, config.shape, filter, size)
        // The bottom area needs a widget of at least two cells in height.
        val tall = size.second >= TALL_DP
        manager.updateAppWidget(widgetId, views(c, widgetId, image, text, loading, state.failed(), tall, if (tall) state.info() else null))
    }

    /** Shows the spinner without resending the picture, which would make the widget flicker. */
    fun showLoading(c: Context, widgetId: Int) {
        if (!exists(c, widgetId)) return
        val views = RemoteViews(c.packageName, R.layout.widget_photo)
        views.setViewVisibility(R.id.loading, View.VISIBLE)
        views.setViewVisibility(R.id.failed, View.GONE)
        AppWidgetManager.getInstance(c).partiallyUpdateAppWidget(widgetId, views)
    }

    private fun views(
        c: Context, widgetId: Int, image: Bitmap, text: String?, loading: Boolean, failed: Boolean, tall: Boolean, info: String?,
    ): RemoteViews {
        val views = RemoteViews(c.packageName, R.layout.widget_photo)
        views.setImageViewBitmap(R.id.image, image)
        views.setTextViewText(R.id.message, text ?: "")
        views.setViewVisibility(R.id.message, if (text == null) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.loading, if (loading) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.failed, if (failed && !loading) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.info, info ?: "")
        views.setViewVisibility(R.id.info, if (info == null) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.zone_bottom, if (tall) View.VISIBLE else View.GONE)
        // Request codes differ per widget, or the taps of one widget would replace those of another.
        views.setOnClickPendingIntent(R.id.zone_left, broadcast(c, PhotoWorker.ACTION_PREV, widgetId, widgetId * 4))
        views.setOnClickPendingIntent(R.id.zone_right, broadcast(c, PhotoWorker.ACTION_NEXT, widgetId, widgetId * 4 + 1))
        views.setOnClickPendingIntent(R.id.zone_bottom, broadcast(c, PhotoWorker.ACTION_INFO, widgetId, widgetId * 4 + 3))
        views.setOnClickPendingIntent(
            R.id.zone_center,
            PendingIntent.getActivity(
                c, widgetId * 4 + 2,
                Intent(c, OpenPhotoActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                PENDING_FLAGS,
            ),
        )
        return views
    }

    /** The picture cropped to fill the widget and cut to [shape]; transparent outside of it. */
    private fun shaped(c: Context, picture: Bitmap?, shape: WidgetShape, filter: PhotoFilter, size: Pair<Int, Int>): Bitmap {
        val density = c.resources.displayMetrics.density
        val (widthDp, heightDp) = size
        // Keeps the bitmap within what a widget may carry.
        val scale = minOf(density, MAX_SIDE_PX / maxOf(widthDp, heightDp))
        val w = (widthDp * scale).toInt()
        val h = (heightDp * scale).toInt()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (picture != null) {
            val fit = maxOf(w.toFloat() / picture.width, h.toFloat() / picture.height)
            paint.colorFilter = filter.colorFilter()
            paint.shader = BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply {
                    setScale(fit, fit)
                    postTranslate((w - picture.width * fit) / 2, (h - picture.height * fit) / 2)
                })
            }
        } else {
            paint.color = Color.parseColor("#222222")
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(result).drawPath(shape.path(w.toFloat(), h.toFloat(), WidgetShape.cornerFor(minOf(w, h).toFloat(), scale)), paint)
        return result
    }

    /** The widget's current width and height in dp. */
    private fun sizeDp(c: Context, options: Bundle): Pair<Int, Int> {
        val portrait = c.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        fun dp(key: String) = options.getInt(key, 0).takeIf { it > 0 } ?: DEFAULT_SIZE_DP
        val width = dp(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val height = dp(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        return width to height
    }

    /** Shows the spinner without resending the picture, which would make the widget flicker. */
    fun showLoading(c: Context) {
        val ids = widgetIds(c)
        if (ids.isEmpty()) return
        val views = RemoteViews(c.packageName, R.layout.widget_photo)
        views.setViewVisibility(R.id.loading, View.VISIBLE)
        AppWidgetManager.getInstance(c).partiallyUpdateAppWidget(ids, views)
    }

    private const val DEFAULT_SIZE_DP = 180
    // Two cells are about 110dp high; one cell is 40-70dp.
    private const val TALL_DP = 100
    private const val MAX_SIDE_PX = 1000f

    private const val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun broadcast(c: Context, action: String, widgetId: Int, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        c, requestCode,
        Intent(c, PhotoWidgetProvider::class.java).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
        PENDING_FLAGS,
    )
}
