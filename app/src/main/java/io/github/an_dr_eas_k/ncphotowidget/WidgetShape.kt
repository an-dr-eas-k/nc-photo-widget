package io.github.an_dr_eas_k.ncphotowidget

import android.graphics.Path
import android.graphics.RectF

/** The outline of the widget picture. The order matches the shape_labels array. */
enum class WidgetShape {
    ROUNDED, SQUARE, CIRCLE, OVAL, PILL, CUT;

    companion object {
        /** A normal rounded corner grows with the smaller side of the widget, within sensible limits. */
        const val CORNER_RATIO = 0.09f

        /** [pxPerDp] converts the limits of 4dp and 28dp. */
        fun cornerFor(side: Float, pxPerDp: Float): Float = (side * CORNER_RATIO).coerceIn(4f * pxPerDp, 28f * pxPerDp)
    }

    /** The outline inside a [width] x [height] box; [corner] is the size of a normal rounded corner. */
    fun path(width: Float, height: Float, corner: Float): Path {
        val path = Path()
        val box = RectF(0f, 0f, width, height)
        val side = minOf(width, height)
        when (this) {
            ROUNDED -> path.addRoundRect(box, corner, corner, Path.Direction.CW)
            SQUARE -> path.addRect(box, Path.Direction.CW)
            CIRCLE -> path.addCircle(width / 2, height / 2, side / 2, Path.Direction.CW)
            OVAL -> path.addOval(box, Path.Direction.CW)
            PILL -> path.addRoundRect(box, side / 2, side / 2, Path.Direction.CW)
            CUT -> {
                val c = corner * 1.2f
                path.moveTo(c, 0f)
                path.lineTo(width - c, 0f)
                path.lineTo(width, c)
                path.lineTo(width, height - c)
                path.lineTo(width - c, height)
                path.lineTo(c, height)
                path.lineTo(0f, height - c)
                path.lineTo(0f, c)
                path.close()
            }
        }
        return path
    }
}
