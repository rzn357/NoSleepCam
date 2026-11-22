package com.example.nosleepcam.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Вертикальний повзунок зуму поверх зображення камери — поведінка скопійована з камери Huawei (виміряно на Honor 10).
 *
 * **У спокої** — кільце з поточним збільшенням і короткий пунктир (три бліді крапки й яскрава кінцева). Кільце стоїть
 * у «слоті» по центру кадра, поки зум менший за 2x, і на 40 dp вище — від 2x; пунктир завжди з іншого боку кільця
 * (вгору на малому зумі, вниз на великому), а яскрава кінцева крапка стоїть там, де кільце буде після перемикання.
 *
 * **Дотики** (див. [ZoomZones]): шкалу відкриває лише дотик на кільці; тап по кінцевій крапці стрибком міняє зум
 * (1x ↔ 2x); усе інше повзунок не чіпає — дотик іде до кадра й запускає фокус.
 *
 * **Шкала** (поки палець на кільці й ще ≈1 с після відпускання): пунктирна лінія симетрично навколо центру кадра, підписи
 * «10x» угорі й «1x» внизу, кільце з крапкою їде за пальцем 1:1 (відносно, палець не на кільці). Шкала логарифмічна:
 * однакові кроки по лінії дають однакову кратність, тож дрібний зум (1x…2x) лишається зручним навіть при великому
 * максимумі. Після відпускання в кільці знову значення, а за ≈1 с шкала згортається (кільце повертається в слот).
 *
 * Усі розміри в dp.
 */
class ZoomSlider @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var minimum = 1.0
    private var maximum = 2.0
    private var current = 1.0
    private var expand = 0f                   // 0 = компактно, 1 = шкала розгорнута
    private var fingerDown = false            // палець на кільці (в кільці крапка, а не значення)
    private var dragStartY = 0f
    private var dragStartPosition = 0.0
    private var tailSide = 0f                 // 0 = кільце в нижньому слоті, пунктир угору; 1 = у верхньому, пунктир униз
    private var tailTarget = 0f
    private var expandedReported = false

    private var gesture = Gesture.None
    private var downX = 0f
    private var downY = 0f

    private var expandAnimator: ValueAnimator? = null
    private var tailAnimator: ValueAnimator? = null
    private var jumpAnimator: ValueAnimator? = null
    private val collapseRunnable = Runnable { animateExpand(0f) }

    private enum class Gesture { None, Drag, Preset }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12f * dp
    }

    /** Користувач змінив збільшення (перетягуванням або стрибком за крапкою). */
    var onValueChanged: ((Double) -> Unit)? = null

    /** Палець торкнувся кільця / відпустив його (для великого числа посеред екрана). */
    var onInteractionStarted: (() -> Unit)? = null

    var onInteractionEnded: (() -> Unit)? = null

    /** Шкала розгорнулась (true) або повністю згорнулась (false) — інші кнопки на цей час ховаються, як у Huawei. */
    var onExpandedChanged: ((Boolean) -> Unit)? = null

    init {
        // Легка тінь, щоб білі елементи читались і на світлому зображенні. setShadowLayer для фігур (не лише тексту)
        // надійно працює лише без апаратного прискорення, а повзунок малий, тож програмного шару достатньо.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        val shadow = Color.argb(153, 0, 0, 0)
        for (paint in listOf(fillPaint, strokePaint, textPaint)) paint.setShadowLayer(3f * dp, 0f, 0f, shadow)
    }

    var minimumZoom: Double
        get() = minimum
        set(value) {
            minimum = maxOf(0.01, value)
            current = current.coerceIn(minimum, maxOf(minimum, maximum))
            updateTailSide(animated = false)
            invalidate()
        }

    var maximumZoom: Double
        get() = maximum
        set(value) {
            maximum = maxOf(value, minimum)
            current = current.coerceIn(minimum, maximum)
            updateTailSide(animated = false)
            invalidate()
        }

    /** Поточне збільшення. Програмна зміна не викликає [onValueChanged]. */
    var value: Double
        get() = current
        set(newValue) {
            current = newValue.coerceIn(minimum, maximum)
            updateTailSide(animated = expand < 0.01f && isAttachedToWindow)
            invalidate()
        }

    // ------------------------------------------------------------------
    // Шкала й геометрія (dp у системі координат вигляду)
    // ------------------------------------------------------------------

    private fun positionOf(value: Double): Double =
        if (maximum <= minimum) 0.0 else (ln(value / minimum) / ln(maximum / minimum)).coerceIn(0.0, 1.0)

    private fun valueAt(position: Double): Double = minimum * (maximum / minimum).pow(position)

    private val heightDp: Float get() = height / dp
    private val trackTop: Float get() = TRACK_INSET                 // позиція максимуму
    private val trackBottom: Float get() = heightDp - TRACK_INSET   // позиція 1x
    private val slotBottom: Float get() = (trackTop + trackBottom) / 2f // центр кадра: кільце на малому зумі
    private val slotTop: Float get() = slotBottom - SLOT_GAP        // кільце від 2x

    /** Де кільце стоїть у спокої: у нижньому слоті, у верхньому або (під час переходу) між ними. */
    private val idleRingY: Float get() = slotBottom + (slotTop - slotBottom) * tailSide

    /** Де кільце зараз: у спокої — у слоті, на розгорнутій шкалі — на позиції свого зуму. */
    private val ringY: Float
        get() {
            val handleY = trackBottom - positionOf(current).toFloat() * (trackBottom - trackTop)
            return idleRingY + (handleY - idleRingY) * expand
        }

    private val trackLengthPx: Float get() = (trackBottom - trackTop) * dp

    /** Із якого боку кільця пунктир: −1 — угору, +1 — униз (за цільовим, а не проміжним значенням). */
    private val tailDirection: Float get() = if (tailTarget < 0.5f) -1f else 1f

    // ------------------------------------------------------------------
    // Жести: рух пальця рахуємо відносно місця дотику, тож кільце не «стрибає» під палець
    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = classify(event.x, event.y)
                when (gesture) {
                    // Не наш дотик: повертаємо false, і він іде до кадра (фокус).
                    Gesture.None -> return false
                    Gesture.Drag -> {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        startInteraction(event.y)
                    }

                    Gesture.Preset -> {
                        downX = event.x
                        downY = event.y
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> when (gesture) {
                Gesture.Drag -> drag(event.y)
                // Палець поїхав — це вже не тап по крапці.
                Gesture.Preset -> if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) gesture = Gesture.None
                Gesture.None -> return false
            }

            MotionEvent.ACTION_UP -> {
                when (gesture) {
                    Gesture.Drag -> {
                        performClick() // для служб доступності
                        finishInteraction()
                    }

                    Gesture.Preset -> jumpToPreset()
                    Gesture.None -> return false
                }
                gesture = Gesture.None
            }

            MotionEvent.ACTION_CANCEL -> {
                if (gesture == Gesture.Drag) finishInteraction()
                gesture = Gesture.None
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Що робить дотик у точці (x, y) цього вигляду: тягне кільце, стрибає за крапкою чи не наш. */
    private fun classify(x: Float, y: Float): Gesture {
        val dx = x / dp - width / dp / 2f
        if (ZoomZones.hitsRing(dx, y / dp - ringY)) return Gesture.Drag

        // Крапка-пресет є лише у згорнутому стані (на розгорнутій шкалі вона не показується).
        if (expand < 0.05f && maximum > minimum) {
            val direction = tailDirection
            val dotY = idleRingY + direction * (RING_RADIUS + TAIL_STEP * TAIL_DOTS)
            if (ZoomZones.hitsPreset(dx, y / dp - dotY, direction)) return Gesture.Preset
        }
        return Gesture.None
    }

    private fun startInteraction(y: Float) {
        jumpAnimator?.cancel()
        fingerDown = true
        dragStartY = y
        dragStartPosition = positionOf(current)
        removeCallbacks(collapseRunnable)
        animateExpand(1f)
        onInteractionStarted?.invoke()
    }

    private fun drag(y: Float) {
        val length = trackLengthPx
        if (length <= 0) return

        var position = (dragStartPosition + (dragStartY - y) / length).coerceIn(0.0, 1.0)
        if (position < SNAP_TO_MIN_POSITION) position = 0.0 // біля низу «прилипаємо» рівно до мінімуму (1x)

        val newValue = valueAt(position)
        if (abs(newValue - current) < 1e-4) return

        current = newValue
        updateTailSide(animated = false) // поки шкала розгорнута, пунктиру не видно — перекидаємо миттєво
        invalidate()
        onValueChanged?.invoke(newValue)
    }

    private fun finishInteraction() {
        fingerDown = false
        invalidate()
        onInteractionEnded?.invoke()
        removeCallbacks(collapseRunnable)
        postDelayed(collapseRunnable, COLLAPSE_DELAY_MS)
    }

    /** Тап по яскравій кінцевій крапці: на малому зумі — до 2x, на великому — назад до мінімуму. */
    private fun jumpToPreset() {
        val target = if (tailTarget < 0.5f) minOf(maximum, TAIL_FLIP_ZOOM) else minimum
        val from = current
        if (abs(target - from) < 1e-3) return

        jumpAnimator?.cancel()
        jumpAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = JUMP_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val value = from * (target / from).pow((it.animatedValue as Float).toDouble())
                current = value
                updateTailSide(animated = true)
                invalidate()
                onValueChanged?.invoke(value)
            }
            start()
        }
    }

    private fun animateExpand(target: Float) {
        expandAnimator?.cancel()
        if (target > 0f && !expandedReported) {
            expandedReported = true
            onExpandedChanged?.invoke(true)
        }

        expandAnimator = ValueAnimator.ofFloat(expand, target).apply {
            duration = if (target > expand) EXPAND_MS else COLLAPSE_MS
            interpolator = DecelerateInterpolator(1.5f) // ≈ кубічне уповільнення (CubicOut)
            addUpdateListener {
                expand = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (expand <= 0f && expandedReported) {
                        expandedReported = false
                        onExpandedChanged?.invoke(false)
                    }
                }
            })
            start()
        }
    }

    /**
     * З якого боку кільця пунктир. Поріг — 2x (як у камері Huawei: 1,9x ще «вгору», 2,0x уже «вниз»); якщо камера
     * не доходить до 2x, береться середина шкали, щоб пунктир усе ж міг перекинутись.
     */
    private fun updateTailSide(animated: Boolean) {
        val threshold = minOf(TAIL_FLIP_ZOOM, sqrt(minimum * maximum))
        val target = if (maximum > minimum && current >= threshold) 1f else 0f
        if (target == tailTarget) return

        tailTarget = target
        tailAnimator?.cancel()
        if (!animated) {
            tailSide = target
            return
        }

        tailAnimator = ValueAnimator.ofFloat(tailSide, target).apply {
            duration = SLOT_SWAP_MS
            addUpdateListener {
                tailSide = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        tailAnimator?.cancel()
        jumpAnimator?.cancel()
        expandAnimator?.cancel()
        removeCallbacks(collapseRunnable)
        if (expandedReported) {
            expandedReported = false
            onExpandedChanged?.invoke(false)
        }
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------
    // Малювання
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val widthDp = width / dp
        if (widthDp <= 0 || heightDp <= 2 * TRACK_INSET) return

        val cx = widthDp / 2f
        val expand = expand
        val ringY = ringY

        if (expand > 0.01f) {
            // Пунктирна лінія вздовж усієї шкали (під кільцем точки не малюємо); кінці — яскравіші.
            val top = trackTop
            val bottom = trackBottom
            var y = bottom
            while (y >= top - 0.1f) {
                if (abs(y - ringY) >= RING_RADIUS + 4f) {
                    val end = abs(y - bottom) < 0.1f || abs(y - top) < 0.1f
                    whiteWithAlpha(fillPaint, expand * (if (end) 1f else 0.9f))
                    canvas.drawCircle(cx * dp, y * dp, (if (end) 1.7f else 1.2f) * dp, fillPaint)
                }
                y -= DOT_SPACING
            }

            // Підписи за кінцями шкали; ховаються, коли кільце підійшло впритул (як у Huawei).
            drawEndLabel(canvas, format(maximum), cx, top - LABEL_OFFSET, ringY, expand)
            drawEndLabel(canvas, format(minimum), cx, bottom + LABEL_OFFSET, ringY, expand)
        }

        if (expand < 0.99f) {
            // «Хвіст» пунктиру: три бліді крапки й яскрава кінцева — підказка, куди можна тягнути кільце (і куди тапнути
            // для стрибка). Перекидається з боку на бік за порогом зуму; під час переходу обидва боки змішуються.
            val visible = 1f - expand
            drawTail(canvas, cx, idleRingY, -1f, visible * (1f - tailSide))
            drawTail(canvas, cx, idleRingY, +1f, visible * tailSide)
        }

        // Кільце: усередині прозоре (без заливки), лише білий контур.
        whiteWithAlpha(strokePaint, 1f)
        canvas.drawCircle(cx * dp, ringY * dp, RING_RADIUS * dp, strokePaint)

        if (fingerDown) {
            // Поки палець на кільці — у ньому крапка; значення видно на великому числі посеред екрана.
            whiteWithAlpha(fillPaint, 1f)
            canvas.drawCircle(cx * dp, ringY * dp, 2.6f * dp, fillPaint)
        } else {
            whiteWithAlpha(textPaint, 1f)
            drawCentered(canvas, format(current), cx, ringY)
        }
    }

    private fun drawEndLabel(canvas: Canvas, text: String, cx: Float, y: Float, ringY: Float, expand: Float) {
        val fade = ((abs(ringY - y) - RING_RADIUS) / 14f).coerceIn(0f, 1f)
        if (fade <= 0.01f) return

        whiteWithAlpha(textPaint, expand * fade)
        drawCentered(canvas, text, cx, y)
    }

    /**
     * Пунктир від кільця: [direction] −1 — угору, +1 — униз. Розміри з виміру камери Huawei: крок 6 dp, перша крапка за
     * 6 dp від краю кільця; три бліді крапки (чим ближче до кільця, тим блідіші) і яскрава кінцева.
     */
    private fun drawTail(canvas: Canvas, cx: Float, ringY: Float, direction: Float, strength: Float) {
        if (strength <= 0.01f) return

        for (i in 1..TAIL_DOTS) {
            val y = ringY + direction * (RING_RADIUS + i * TAIL_STEP)
            val last = i == TAIL_DOTS
            val alpha = if (last) 1f else 0.15f + 0.12f * i
            whiteWithAlpha(fillPaint, alpha * strength)
            canvas.drawCircle(cx * dp, y * dp, (if (last) 1.7f else 1.0f) * dp, fillPaint)
        }
    }

    /** Білий колір із заданою непрозорістю (спершу колір — він скидає альфу). */
    private fun whiteWithAlpha(paint: Paint, alpha: Float) {
        paint.color = Color.WHITE
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
    }

    /** Текст по центру точки (cx, cy), координати в dp. */
    private fun drawCentered(canvas: Canvas, text: String, cx: Float, cy: Float) {
        val metrics = textPaint.fontMetrics
        canvas.drawText(text, cx * dp, cy * dp - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }

    companion object {
        /** Відступ краю шкали від верху/низу вигляду (dp): місце для підписів і пунктиру під кільцем у слоті. */
        const val TRACK_INSET = 52f

        private const val RING_RADIUS = 16.5f
        private const val TAIL_STEP = 6f          // крок пунктиру біля кільця, dp
        private const val TAIL_DOTS = 4           // три бліді + яскрава кінцева
        private const val SLOT_GAP = RING_RADIUS + TAIL_STEP * TAIL_DOTS // відстань між слотами = до кінцевої крапки
        private const val TAIL_FLIP_ZOOM = 2.0    // від цього зуму кільце у верхньому слоті, а пунктир іде вниз
        private const val DOT_SPACING = 6f
        private const val LABEL_OFFSET = 15f      // підпис «10x»/«1x» за кінцем шкали
        private const val SNAP_TO_MIN_POSITION = 0.012

        private const val COLLAPSE_DELAY_MS = 1000L // скільки шкала лишається після відпускання (виміряно в Huawei)
        private const val EXPAND_MS = 180L
        private const val COLLAPSE_MS = 380L
        private const val SLOT_SWAP_MS = 220L
        private const val JUMP_MS = 250L

        private var formatter: DecimalFormat? = null
        private var bigFormatter: DecimalFormat? = null
        private var formatterLocale: Locale? = null

        /** Кеш форматувальників скидається, якщо мова пристрою змінилась. Лише з UI-потоку. */
        private fun currentLocale(): Locale {
            val locale = Locale.getDefault()
            if (formatterLocale != locale) {
                formatter = null
                bigFormatter = null
                formatterLocale = locale
            }
            return locale
        }

        private fun newFormat(pattern: String, locale: Locale) =
            DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale)).apply { roundingMode = RoundingMode.HALF_UP }

        /** Текст збільшення без «x», з урахуванням мови пристрою: «1,5» або «2». Лише з UI-потоку. */
        fun formatNumber(zoom: Double): String {
            val locale = currentLocale()
            return (formatter ?: newFormat("0.#", locale).also { formatter = it }).format(zoom)
        }

        /** Велике число посеред екрана завжди з десятковим знаком, як у Huawei («2,0»). Лише з UI-потоку. */
        fun formatBigNumber(zoom: Double): String {
            val locale = currentLocale()
            return (bigFormatter ?: newFormat("0.0", locale).also { bigFormatter = it }).format(zoom)
        }

        fun format(zoom: Double): String = formatNumber(zoom) + "x"
    }
}
