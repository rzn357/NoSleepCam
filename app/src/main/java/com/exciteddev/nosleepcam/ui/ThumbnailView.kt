package com.exciteddev.nosleepcam.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * A small circle in the bottom-left corner with the last photo (as in the Huawei camera): 40 dp, the photo is cropped to a circle
 * with a thin white rim. Not shown when empty. Tapping it opens the photo in the gallery.
 */
class ThumbnailView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val dp = resources.displayMetrics.density
        private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val ringPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * dp
                color = Color.argb(230, 255, 255, 255)
            }
        private val matrix = Matrix()

        private var bitmap: Bitmap? = null

        /** Current thumbnail photo; null — the circle is hidden. */
        var image: Bitmap?
            get() = bitmap
            set(value) {
                bitmap = value
                photoPaint.shader = value?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
                // INVISIBLE, not GONE: the circle is laid out even before the first photo, so the animation knows where to fly.
                visibility = if (value != null) VISIBLE else INVISIBLE
                invalidate()
            }

        /** A short "bounce" when a new photo flies into the circle. */
        fun pop() {
            animate().cancel()
            animate()
                .scaleX(1.18f)
                .scaleY(1.18f)
                .setDuration(90)
                .withEndAction {
                    animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(140)
                        .start()
                }.start()
        }

        override fun onDraw(canvas: Canvas) {
            val bitmap = bitmap ?: return
            val size = minOf(width, height).toFloat()
            val radius = size / 2f

            // Photo centred in the circle without distortion (like centerCrop).
            val scale = maxOf(size / bitmap.width, size / bitmap.height)
            matrix.setScale(scale, scale)
            matrix.postTranslate((width - bitmap.width * scale) / 2f, (height - bitmap.height * scale) / 2f)
            photoPaint.shader?.setLocalMatrix(matrix)

            canvas.drawCircle(width / 2f, height / 2f, radius - ringPaint.strokeWidth / 2f, photoPaint)
            canvas.drawCircle(width / 2f, height / 2f, radius - ringPaint.strokeWidth / 2f, ringPaint)
        }
    }
