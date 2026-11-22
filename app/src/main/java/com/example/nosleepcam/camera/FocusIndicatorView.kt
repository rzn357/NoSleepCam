package com.example.nosleepcam.camera

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Індикатор фокусування по дотику, як у камері Huawei: квадрат із чотирьох кутових дужок у точці дотику та «сонечко»
 * (яскравість) праворуч від нього.
 *
 * Якщо потягнути «сонечко» вгору/вниз, квадрат ховається, з'являється вертикальна пунктирна лінія, «сонечко» їздить по
 * ній, а зліва показується значення експокомпенсації (EV). Після відпускання квадрат повертається, яскравість лишається,
 * а за кілька секунд усе згасає. Новий дотик скидає яскравість.
 *
 * Сам елемент дотики не обробляє — це робить [CameraPreviewView] (через [hitSun], [beginDrag], [dragTo], [endDrag]).
 * Усі розміри в dp взято з вимірів камери Huawei на Honor 10.
 */
internal class FocusIndicatorView(context: Context) : View(context) {

    /** Користувач змінив яскравість «сонечком»: новий індекс експокомпенсації. */
    var onExposureChanged: ((Int) -> Unit)? = null

    private val dp = context.resources.displayMetrics.density

    private val bracketPaint = paint(Paint.Style.STROKE, 1.4f)
    private val sunPaint = paint(Paint.Style.STROKE, 1.2f)
    private val dotPaint = paint(Paint.Style.FILL, 0f)
    private val shadowPaint = paint(Paint.Style.STROKE, 3f).apply { color = Color.argb(70, 0, 0, 0) }
    private val shadowFill = paint(Paint.Style.FILL, 0f).apply { color = Color.argb(70, 0, 0, 0) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textSize = LABEL_SIZE * dp
        color = Color.WHITE
        setShadowLayer(4f * dp, 0f, 0f, Color.argb(120, 0, 0, 0))
    }
    private val path = Path()

    // ---- Стан ----
    private var centerX = 0f
    private var centerY = 0f
    private var sunSide = 1f          // +1: «сонечко» праворуч від квадрата, −1: ліворуч (коли справа немає місця)
    private var status = FocusStatus.Searching
    private var evMin = 0
    private var evMax = 0
    private var evStep = 0f
    private var evIndex = 0
    private var sunOffset = 0f        // px від центру квадрата; додатне — вниз (темніше)
    private var dragStartY = 0f
    private var dragStartOffset = 0f
    private var dragging = false

    // ---- Анімовані значення (0…1) ----
    private var masterAlpha = 0f      // весь індикатор
    private var squareAlpha = 1f      // квадрат (ховається, поки тягнемо «сонечко»)
    private var lineAlpha = 0f        // пунктирна шкала
    private var labelAlpha = 0f       // значення EV: лише поки тягнемо «сонечко»
    private var squareScale = 1f
    private val masterTween = Tween()
    private val squareTween = Tween()
    private val lineTween = Tween()
    private val labelTween = Tween()
    private val scaleTween = Tween()
    private val hideRunnable = Runnable { fadeOut() }

    private fun paint(style: Paint.Style, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = style
        strokeWidth = widthDp * dp
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    /** Яскравість доступна лише там, де камера має діапазон експокомпенсації (і не у високошвидкісному режимі). */
    private val evEnabled: Boolean get() = evMax > evMin

    private val sunX: Float get() = centerX + sunSide * SUN_DISTANCE * dp
    private val sunY: Float get() = centerY + sunOffset
    private val halfLine: Float get() = LINE_HALF_LENGTH * dp

    // ------------------------------------------------------------------
    // API для CameraPreviewView
    // ------------------------------------------------------------------

    /** Діапазон яскравості поточної камери (з геометрії попереднього перегляду). */
    fun configure(geometry: PreviewGeometry) {
        evMin = geometry.evMin
        evMax = geometry.evMax
        evStep = geometry.evStep
        if (!geometry.canTap) hideNow()
        invalidate()
    }

    /** Дотик у точці (x, y): показуємо квадрат (з коротким «наближенням»), яскравість скидаємо. */
    fun showAt(x: Float, y: Float) {
        removeCallbacks(hideRunnable)
        dragging = false
        centerX = x
        centerY = y
        // Праворуч від квадрата лишається місце для «сонечка» й підпису; якщо ні — ставимо ліворуч.
        sunSide = if (x + (SUN_DISTANCE + SUN_RADIUS + 8f) * dp <= width) 1f else -1f
        evIndex = 0
        sunOffset = 0f
        status = FocusStatus.Searching

        masterTween.run(masterAlpha, 1f, 80) { masterAlpha = it }
        squareTween.run(squareAlpha, 1f, 80) { squareAlpha = it }
        lineTween.run(lineAlpha, 0f, 80) { lineAlpha = it }
        labelTween.run(labelAlpha, 0f, 80) { labelAlpha = it }
        scaleTween.run(1.3f, 1f, 180) { squareScale = it }
        invalidate()
    }

    /** Результат фокусування: колір дужок; за кілька секунд індикатор згасає. */
    fun setStatus(newStatus: FocusStatus) {
        if (newStatus == FocusStatus.Searching) return

        status = newStatus
        invalidate()
        if (!dragging) scheduleHide()
    }

    /** Чи потрапляє дотик на «сонечко» (з запасом на палець). */
    fun hitSun(x: Float, y: Float): Boolean =
        evEnabled && masterAlpha > 0.3f && hypot(x - sunX, y - sunY) <= GRAB_RADIUS * dp

    fun beginDrag(y: Float) {
        removeCallbacks(hideRunnable)
        dragging = true
        dragStartY = y
        dragStartOffset = sunOffset
        squareTween.run(squareAlpha, 0f, 120) { squareAlpha = it }
        lineTween.run(lineAlpha, 1f, 120) { lineAlpha = it }
        labelTween.run(labelAlpha, 1f, 120) { labelAlpha = it }
    }

    /** Рух пальця: «сонечко» слідує за ним уздовж лінії; повертає новий індекс, лише якщо він змінився. */
    fun dragTo(y: Float) {
        if (!dragging) return

        sunOffset = (dragStartOffset + (y - dragStartY)).coerceIn(-halfLine, halfLine)
        val index = ExposureCompensation.indexFor(-sunOffset / halfLine, evMin, evMax)
        if (index != evIndex) {
            evIndex = index
            onExposureChanged?.invoke(index)
        }
        invalidate()
    }

    fun endDrag() {
        if (!dragging) return

        dragging = false
        // «Сонечко» стає точно на позицію обраного значення.
        sunOffset = -ExposureCompensation.fractionFor(evIndex, evMin, evMax) * halfLine
        squareTween.run(squareAlpha, 1f, 160) { squareAlpha = it }
        labelTween.run(labelAlpha, 0f, 150) { labelAlpha = it } // як у Huawei: після відпускання значення зникає
        scheduleHide()
        invalidate()
    }

    /** Негайно ховає індикатор (зміна орієнтації, камери чи режиму). */
    fun hideNow() {
        removeCallbacks(hideRunnable)
        dragging = false
        masterTween.cancel()
        masterAlpha = 0f
        lineAlpha = 0f
        labelAlpha = 0f
        invalidate()
    }

    private fun scheduleHide() {
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, VISIBLE_AFTER_RESULT_MS)
    }

    private fun fadeOut() {
        masterTween.run(masterAlpha, 0f, 300) { masterAlpha = it }
    }

    /** Одна анімація значення; нова зупиняє попередню. */
    private inner class Tween {
        private var animator: ValueAnimator? = null

        fun run(from: Float, to: Float, ms: Long, apply: (Float) -> Unit) {
            animator?.cancel()
            if (from == to) {
                apply(to)
                invalidate()
                return
            }

            animator = ValueAnimator.ofFloat(from, to).apply {
                duration = ms
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    apply(it.animatedValue as Float)
                    invalidate()
                }
                start()
            }
        }

        fun cancel() = animator?.cancel()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideRunnable)
        masterTween.cancel()
        squareTween.cancel()
        lineTween.cancel()
        labelTween.cancel()
        scaleTween.cancel()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------
    // Малювання
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (masterAlpha <= 0.01f) return

        if (squareAlpha > 0.01f) drawSquare(canvas)
        if (evEnabled) {
            if (lineAlpha > 0.01f) drawLine(canvas)
            if (labelAlpha > 0.01f) drawLabel(canvas)
            drawSun(canvas)
        }
    }

    private fun alpha(factor: Float) = (255 * masterAlpha * factor).coerceIn(0f, 255f).toInt()

    /** Чотири кутові дужки із заокругленими кутами; колір — за результатом фокусування. */
    private fun drawSquare(canvas: Canvas) {
        val half = SQUARE_HALF * dp * squareScale
        val arm = BRACKET_ARM * dp
        val radius = BRACKET_RADIUS * dp
        val color = when (status) {
            FocusStatus.Focused -> Color.rgb(0x4C, 0xD9, 0x64)
            FocusStatus.Failed -> Color.rgb(0xFF, 0x3B, 0x30)
            else -> Color.WHITE
        }
        bracketPaint.color = color
        bracketPaint.alpha = alpha(squareAlpha)
        shadowPaint.alpha = (70 * masterAlpha * squareAlpha).toInt()

        for (sx in intArrayOf(-1, 1)) {
            for (sy in intArrayOf(-1, 1)) {
                // Кут (cx±half, cy±half): дві «руки» довжиною arm і заокруглення між ними.
                val x = centerX + sx * half
                val y = centerY + sy * half
                path.reset()
                path.moveTo(x, y - sy * arm)
                path.lineTo(x, y - sy * radius)
                path.quadTo(x, y, x - sx * radius, y)
                path.lineTo(x - sx * arm, y)
                canvas.drawPath(path, shadowPaint)
                canvas.drawPath(path, bracketPaint)
            }
        }
    }

    /** «Сонечко»: коло з вісьмома променями. */
    private fun drawSun(canvas: Canvas) {
        val x = sunX
        val y = sunY
        val inner = SUN_CIRCLE * dp
        val rayFrom = SUN_RAY_FROM * dp
        val rayTo = SUN_RAY_TO * dp
        sunPaint.alpha = alpha(1f)
        shadowPaint.alpha = (70 * masterAlpha).toInt()

        canvas.drawCircle(x, y, inner, shadowPaint)
        canvas.drawCircle(x, y, inner, sunPaint)
        for (i in 0 until 8) {
            val angle = Math.PI / 4 * i
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            canvas.drawLine(x + dx * rayFrom, y + dy * rayFrom, x + dx * rayTo, y + dy * rayTo, shadowPaint)
            canvas.drawLine(x + dx * rayFrom, y + dy * rayFrom, x + dx * rayTo, y + dy * rayTo, sunPaint)
        }
    }

    /** Вертикальна пунктирна лінія крізь «сонечко» — шкала яскравості. */
    private fun drawLine(canvas: Canvas) {
        val x = sunX
        val spacing = DOT_SPACING * dp
        val radius = DOT_RADIUS * dp
        dotPaint.color = Color.WHITE
        dotPaint.alpha = alpha(lineAlpha * 0.85f)
        shadowFill.alpha = (70 * masterAlpha * lineAlpha).toInt()

        var y = centerY - halfLine
        while (y <= centerY + halfLine + 0.1f) {
            // Під «сонечком» точки не малюємо.
            if (abs(y - sunY) > (SUN_RAY_TO + 3f) * dp) {
                canvas.drawCircle(x, y, radius + 0.6f * dp, shadowFill)
                canvas.drawCircle(x, y, radius, dotPaint)
            }
            y += spacing
        }
    }

    /** Велике значення EV збоку від «сонечка», по центру його висоти. */
    private fun drawLabel(canvas: Canvas) {
        val text = ExposureCompensation.format(evIndex, evStep)
        textPaint.alpha = alpha(labelAlpha)
        val metrics = textPaint.fontMetrics
        val baseline = sunY - (metrics.ascent + metrics.descent) / 2f
        // Підпис з того боку «сонечка», що звернений від квадрата.
        textPaint.textAlign = if (sunSide > 0) Paint.Align.RIGHT else Paint.Align.LEFT
        val x = if (sunSide > 0) sunX - LABEL_GAP * dp else sunX + LABEL_GAP * dp
        canvas.drawText(text, x, baseline, textPaint)
    }

    private companion object {
        const val SQUARE_HALF = 40f          // квадрат 80×80 dp
        const val BRACKET_ARM = 19f          // довжина «руки» кутової дужки
        const val BRACKET_RADIUS = 4f        // заокруглення кута
        const val SUN_DISTANCE = 66.7f       // від центру квадрата до «сонечка»
        const val SUN_CIRCLE = 4.5f
        const val SUN_RAY_FROM = 7.5f
        const val SUN_RAY_TO = 10.5f
        const val SUN_RADIUS = 10.5f
        const val GRAB_RADIUS = 30f          // зона захоплення «сонечка» пальцем
        const val LINE_HALF_LENGTH = 48f     // пунктирна шкала: ±48 dp від центру квадрата
        const val DOT_SPACING = 5f
        const val DOT_RADIUS = 0.8f
        const val LABEL_SIZE = 36f
        const val LABEL_GAP = 28f
        const val VISIBLE_AFTER_RESULT_MS = 2500L
    }
}
