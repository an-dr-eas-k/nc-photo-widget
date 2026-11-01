package io.github.an_dr_eas_k.ncphotowidget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

/** A small landscape drawn inside the outline of a [WidgetShape]. */
class ShapePreviewView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var shape = WidgetShape.ROUNDED
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // A real widget is about 180dp wide, with 16dp corners.
        val corner = 16 * resources.displayMetrics.density * w / (180 * resources.displayMetrics.density)
        canvas.save()
        canvas.clipPath(shape.path(w, h, corner))

        paint.color = MaterialColors.getColor(this, MaterialR.attr.colorPrimaryContainer)
        canvas.drawRect(0f, 0f, w, h, paint)

        paint.color = MaterialColors.getColor(this, MaterialR.attr.colorTertiary)
        canvas.drawCircle(w * 0.72f, h * 0.32f, h * 0.12f, paint)

        paint.color = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        val hills = Path().apply {
            moveTo(0f, h)
            lineTo(0f, h * 0.75f)
            lineTo(w * 0.30f, h * 0.42f)
            lineTo(w * 0.55f, h * 0.72f)
            lineTo(w * 0.72f, h * 0.58f)
            lineTo(w, h * 0.82f)
            lineTo(w, h)
            close()
        }
        canvas.drawPath(hills, paint)
        canvas.restore()
    }
}
