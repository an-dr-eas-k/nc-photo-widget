package io.github.an_dr_eas_k.ncphotowidget

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/** A colour treatment applied to the picture before it is drawn on the widget. The order matches the filter_labels array. */
enum class PhotoFilter {
    NONE, GRAYSCALE, NOIR, SEPIA, VIVID, FADED, WARM, COOL, FILM;

    /** Null for [NONE]. */
    fun colorFilter(): ColorMatrixColorFilter? = matrix()?.let { ColorMatrixColorFilter(it) }

    private fun matrix(): ColorMatrix? = when (this) {
        NONE -> null
        GRAYSCALE -> ColorMatrix().apply { setSaturation(0f) }
        NOIR -> ColorMatrix().apply { setSaturation(0f) }.apply { postConcat(contrast(1.35f)) }
        SEPIA -> ColorMatrix(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        VIVID -> ColorMatrix().apply { setSaturation(1.35f) }.apply { postConcat(contrast(1.1f)) }
        // Lifted blacks and softer whites, a little less colour.
        FADED -> ColorMatrix().apply { setSaturation(0.85f) }.apply { postConcat(scaleAndLift(0.86f, 0.86f, 0.86f, 30f, 30f, 30f)) }
        WARM -> ColorMatrix(diagonal(1.08f, 1.0f, 0.88f))
        COOL -> ColorMatrix(diagonal(0.9f, 1.0f, 1.1f))
        // A faded film look with teal shadows and warm highlights, estimated from a sample photo.
        FILM -> ColorMatrix().apply { setSaturation(1.1f) }.apply {
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        0.88f, 0f, 0f, 0f, 22f,
                        0f, 0.86f, 0f, 0f, 30f,
                        0f, 0.10f, 0.72f, 0f, 36f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
    }

    private fun contrast(c: Float) = ColorMatrix(
        floatArrayOf(
            c, 0f, 0f, 0f, 128f * (1 - c),
            0f, c, 0f, 0f, 128f * (1 - c),
            0f, 0f, c, 0f, 128f * (1 - c),
            0f, 0f, 0f, 1f, 0f,
        ),
    )

    private fun scaleAndLift(r: Float, g: Float, b: Float, lr: Float, lg: Float, lb: Float) = ColorMatrix(
        floatArrayOf(
            r, 0f, 0f, 0f, lr,
            0f, g, 0f, 0f, lg,
            0f, 0f, b, 0f, lb,
            0f, 0f, 0f, 1f, 0f,
        ),
    )

    private fun diagonal(r: Float, g: Float, b: Float) = floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}
