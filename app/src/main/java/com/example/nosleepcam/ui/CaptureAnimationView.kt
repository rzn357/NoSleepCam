package com.example.nosleepcam.ui

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
 * Анімація знімка: екран «блимає», як від затвора, а застигле зображення зменшується, округлюється й летить у коло
 * з останнім знімком у лівому нижньому куті.
 *
 * Малює лише поки триває анімація й дотики не обробляє. Координати [play] — у системі цього вигляду
 * (він накладається на попередній перегляд і мініатюру).
 */
class CaptureAnimationView(context: Context) : View(context) {

    private val dp = resources.displayMetrics.density
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
    private var flight = 0f // 0 — зображення на місці прев'ю, 1 — уже в колі
    private var blink = 0f  // 0…1…0: затемнення «затвора»
    private var blinkAnimator: ValueAnimator? = null
    private var flightAnimator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
    }

    /**
     * Запускає анімацію. [image] — застигле прев'ю, [imageRect] — область зображення в ньому (без чорних полів),
     * [fromRect] — де це зображення стоїть на екрані, [toRect] — межі кола-мініатюри. [onEnd] — коли зображення
     * долетіло (викликається й при перериванні).
     */
    fun play(image: Bitmap, imageRect: Rect, fromRect: RectF, toRect: RectF, onEnd: () -> Unit) {
        cancelRunning()
        bitmap = image
        imageInBitmap.set(imageRect)
        from.set(fromRect)
        to.set(toRect)
        flight = 0f
        blink = 0f

        // «Затвор»: коротке затемнення всього екрана.
        blinkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (BLINK_MS * timeScale).toLong()
            addUpdateListener {
                blink = kotlin.math.sin(Math.PI * (it.animatedValue as Float)).toFloat()
                invalidate()
            }
            start()
        }

        // Політ: після короткої паузи — зменшення, округлення й рух у мініатюру.
        flightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = (FLIGHT_DELAY_MS * timeScale).toLong()
            duration = (FLIGHT_MS * timeScale).toLong()
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener {
                flight = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    bitmap = null
                    invalidate()
                    if (!cancelled) onEnd()
                }
            })
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

        // Поточна рамка: від зображення на екрані до кола; кути плавно округлюються до півдовжини меншої сторони.
        rect.set(
            lerp(from.left, to.left, flight), lerp(from.top, to.top, flight),
            lerp(from.right, to.right, flight), lerp(from.bottom, to.bottom, flight),
        )
        val radius = minOf(rect.width(), rect.height()) / 2f * flight

        // Фрагмент знімка під пропорції рамки (centerCrop), щоб зображення не стискалось.
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

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        /** Лише для діагностики (збірка з -PstartupTrace=true): уповільнення анімації, щоб розглянути її покадрово. */
        var timeScale = 1f

        /** Скільки триває «блимання» затвора й коли починається політ (мс); весь знімок ≈ 0,5 с. */
        private const val BLINK_MS = 160L
        private const val FLIGHT_DELAY_MS = 90L
        private const val FLIGHT_MS = 360L
        private const val BLINK_STRENGTH = 0.6f
    }
}
