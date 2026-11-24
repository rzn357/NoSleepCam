package com.exciteddev.nosleepcam.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.animation.PathInterpolator

/**
 * Capture animation: the screen "blinks" like a shutter, and the frozen image shrinks, rounds off and flies into the circle
 * with the last photo in the bottom-left corner.
 *
 * Draws only while the animation runs and does not handle touches. The coordinates of [play] are in this view's system
 * (it overlays the preview and the thumbnail).
 */
class CaptureAnimationView(
    context: Context,
) : View(context) {
    private val dp = resources.displayMetrics.density
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * dp
            color = Color.WHITE
        }
    private val blinkPaint = Paint().apply { color = Color.BLACK }
    private val path = Path()
    private val rect = RectF()
    private val src = Rect()

    private var bitmap: Bitmap? = null
    private val imageInBitmap = Rect()
    private val from = RectF()
    private val to = RectF()
    private var flight = 0f // 0 — image in place of the preview, 1 — already in the circle
    private var blink = 0f // 0…1…0: "shutter" darkening
    private var blinkAnimator: ValueAnimator? = null
    private var flightAnimator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
    }

    /**
     * Starts the animation. [image] is the frozen preview, [imageRect] is the image area within it (without black bars),
     * [fromRect] is where this image is on screen, [toRect] is the bounds of the thumbnail circle. [onEnd] — when the image
     * has arrived (also called on interruption).
     */
    fun play(
        image: Bitmap,
        imageRect: Rect,
        fromRect: RectF,
        toRect: RectF,
        onEnd: () -> Unit,
    ) {
        cancelRunning()
        bitmap = image
        imageInBitmap.set(imageRect)
        from.set(fromRect)
        to.set(toRect)
        flight = 0f
        blink = 0f

        // "Shutter": a short darkening of the whole screen.
        blinkAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = (BLINK_MS * timeScale).toLong()
                addUpdateListener {
                    blink = kotlin.math.sin(Math.PI * (it.animatedValue as Float)).toFloat()
                    invalidate()
                }
                start()
            }

        // Flight: after a short pause — shrinking, rounding and moving into the thumbnail.
        flightAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = (FLIGHT_DELAY_MS * timeScale).toLong()
                duration = (FLIGHT_MS * timeScale).toLong()
                interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
                addUpdateListener {
                    flight = it.animatedValue as Float
                    invalidate()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        private var cancelled = false

                        override fun onAnimationCancel(animation: Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            bitmap = null
                            invalidate()
                            if (!cancelled) onEnd()
                        }
                    },
                )
                start()
            }
        invalidate()
    }

    private fun cancelRunning() {
        blinkAnimator?.cancel()
        flightAnimator?.cancel()
    }

    override fun onDetachedFromWindow() {
        cancelRunning()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = bitmap ?: return

        // Current frame: from the image on screen to the circle; the corners smoothly round off to half of the shorter side.
        rect.set(
            lerp(from.left, to.left, flight),
            lerp(from.top, to.top, flight),
            lerp(from.right, to.right, flight),
            lerp(from.bottom, to.bottom, flight),
        )
        val radius = minOf(rect.width(), rect.height()) / 2f * flight

        // A fragment of the photo matching the frame's proportions (centerCrop), so that the image is not squashed.
        val aspect = rect.width() / rect.height()
        val imageAspect = imageInBitmap.width().toFloat() / imageInBitmap.height()
        if (imageAspect > aspect) {
            val w = (imageInBitmap.height() * aspect).toInt()
            val left = imageInBitmap.centerX() - w / 2
            src.set(left, imageInBitmap.top, left + w, imageInBitmap.bottom)
        } else {
            val h = (imageInBitmap.width() / aspect).toInt()
            val top = imageInBitmap.centerY() - h / 2
            src.set(imageInBitmap.left, top, imageInBitmap.right, top + h)
        }

        canvas.save()
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(path)
        canvas.drawBitmap(bitmap, src, rect, photoPaint)
        canvas.restore()

        if (flight > 0f) {
            borderPaint.alpha = (255 * flight).toInt()
            canvas.drawRoundRect(rect, radius, radius, borderPaint)
        }

        if (blink > 0.01f) {
            blinkPaint.alpha = (BLINK_STRENGTH * 255 * blink).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), blinkPaint)
        }
    }

    private fun lerp(
        a: Float,
        b: Float,
        t: Float,
    ) = a + (b - a) * t

    companion object {
        /** Diagnostics only (build with -PstartupTrace=true): slows the animation down to examine it frame by frame. */
        var timeScale = 1f

        /** How long the shutter "blink" lasts and when the flight starts (ms); the whole capture ≈ 0.5 s. */
        private const val BLINK_MS = 160L
        private const val FLIGHT_DELAY_MS = 90L
        private const val FLIGHT_MS = 360L
        private const val BLINK_STRENGTH = 0.6f
    }
}
