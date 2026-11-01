package io.github.an_dr_eas_k.ncphotowidget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** A small, colourful scene with a [PhotoFilter] applied, so the effect of each filter can be seen. */
class FilterPreviewView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var filter = PhotoFilter.NONE
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = 16 * resources.displayMetrics.density
        canvas.save()
        canvas.clipPath(WidgetShape.ROUNDED.path(w, h, corner))
        paint.colorFilter = filter.colorFilter()

        fun fill(color: String) { paint.color = Color.parseColor(color) }

        fill("#4FA3E0")
        canvas.drawRect(0f, 0f, w, h, paint)
        fill("#FFD84D")
        canvas.drawCircle(w * 0.78f, h * 0.28f, h * 0.13f, paint)
        fill("#2E7D3A")
        canvas.drawPath(hills(w, h, 0.62f, 0.5f), paint)
        fill("#4CAF50")
        canvas.drawPath(hills(w, h, 0.78f, 0.3f), paint)
        // A red house with a dark roof.
        fill("#D9503F")
        canvas.drawRect(w * 0.18f, h * 0.55f, w * 0.40f, h * 0.82f, paint)
        fill("#7A3B2E")
        canvas.drawPath(Path().apply {
            moveTo(w * 0.15f, h * 0.55f)
            lineTo(w * 0.29f, h * 0.38f)
            lineTo(w * 0.43f, h * 0.55f)
            close()
        }, paint)
        fill("#D2B48C")
        canvas.drawRect(0f, h * 0.86f, w, h, paint)
        canvas.restore()
    }

    private fun hills(w: Float, h: Float, base: Float, peak: Float) = Path().apply {
        moveTo(0f, h)
        lineTo(0f, h * base)
        lineTo(w * 0.35f, h * peak)
        lineTo(w * 0.7f, h * base)
        lineTo(w, h * (base - 0.05f))
        lineTo(w, h)
        close()
    }
}
