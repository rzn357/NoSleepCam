package com.exciteddev.nosleepcam.ui

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
 * Vertical zoom slider over the camera image — the behaviour is copied from the Huawei camera (measured on the Honor 10).
 *
 * **At rest** — a ring with the current magnification and a short dotted tail (three faint dots and a bright end one). The ring sits
 * in a "slot" at the frame centre while the zoom is below 2x, and 40 dp higher from 2x on; the dotted tail is always on the other side of the ring
 * (up at low zoom, down at high zoom), and the bright end dot sits where the ring will be after switching.
 *
 * **Touches** (see [ZoomZones]): only a touch on the ring opens the scale; a tap on the end dot switches the zoom in a jump
 * (1x ↔ 2x); the slider ignores everything else — the touch goes to the frame and triggers focus.
 *
 * **Scale** (while a finger is on the ring and for ≈1 s after release): a dotted line symmetric around the frame centre, labels
 * "10x" at the top and "1x" at the bottom, the ring with a dot follows the finger 1:1 (relatively; the finger is not on the ring). The scale is logarithmic:
 * equal steps along the line give equal ratios, so fine zoom (1x…2x) stays convenient even with a large
 * maximum. After release the ring shows the value again, and after ≈1 s the scale collapses (the ring returns to its slot).
 *
 * All sizes are in dp.
 */
class ZoomSlider
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val dp = resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        private var minimum = 1.0
        private var maximum = 2.0
        private var current = 1.0
        private var expand = 0f // 0 = compact, 1 = scale expanded
        private var fingerDown = false // finger on the ring (the ring shows a dot, not the value)
        private var dragStartY = 0f
        private var dragStartPosition = 0.0
        private var tailSide = 0f // 0 = ring in the lower slot, tail up; 1 = in the upper slot, tail down
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
        private val strokePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * dp
            }
        private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = 12f * dp
            }

        /** The user changed the magnification (by dragging or by jumping via the dot). */
        var onValueChanged: ((Double) -> Unit)? = null

        /** A finger touched the ring / lifted from it (for the big number in the middle of the screen). */
        var onInteractionStarted: (() -> Unit)? = null

        var onInteractionEnded: (() -> Unit)? = null

        /** The scale expanded (true) or fully collapsed (false) — the other buttons are hidden meanwhile, as in Huawei. */
        var onExpandedChanged: ((Boolean) -> Unit)? = null

        init {
            // A light shadow so that the white elements are readable on a bright image too. setShadowLayer for shapes (not just text)
            // works reliably only without hardware acceleration, and the slider is small, so a software layer is enough.
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

        /** Current magnification. A programmatic change does not call [onValueChanged]. */
        var value: Double
            get() = current
            set(newValue) {
                current = newValue.coerceIn(minimum, maximum)
                updateTailSide(animated = expand < 0.01f && isAttachedToWindow)
                invalidate()
            }

        // ------------------------------------------------------------------
        // Scale and geometry (dp in the view's coordinate system)
        // ------------------------------------------------------------------

        private fun positionOf(value: Double): Double =
            if (maximum <= minimum) 0.0 else (ln(value / minimum) / ln(maximum / minimum)).coerceIn(0.0, 1.0)

        private fun valueAt(position: Double): Double = minimum * (maximum / minimum).pow(position)

        private val heightDp: Float get() = height / dp
        private val trackTop: Float get() = TRACK_INSET // position of the maximum
        private val trackBottom: Float get() = heightDp - TRACK_INSET // position of 1x
        private val slotBottom: Float get() = (trackTop + trackBottom) / 2f // frame centre: the ring at low zoom
        private val slotTop: Float get() = slotBottom - SLOT_GAP // ring from 2x

        /** Where the ring sits at rest: in the lower slot, in the upper one or (during a transition) between them. */
        private val idleRingY: Float get() = slotBottom + (slotTop - slotBottom) * tailSide

        /** Where the ring is now: at rest — in the slot, on the expanded scale — at the position of its zoom. */
        private val ringY: Float
            get() {
                val handleY = trackBottom - positionOf(current).toFloat() * (trackBottom - trackTop)
                return idleRingY + (handleY - idleRingY) * expand
            }

        private val trackLengthPx: Float get() = (trackBottom - trackTop) * dp

        /** Which side of the ring the dotted tail is on: −1 — up, +1 — down (by the target value, not the intermediate one). */
        private val tailDirection: Float get() = if (tailTarget < 0.5f) -1f else 1f

        // ------------------------------------------------------------------
        // Gestures: the finger movement is computed relative to the touch point, so the ring does not "jump" under the finger
        // ------------------------------------------------------------------

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gesture = classify(event.x, event.y)
                    when (gesture) {
                        // Not our touch: return false, and it goes to the frame (focus).
                        Gesture.None -> {
                            return false
                        }

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

                MotionEvent.ACTION_MOVE -> {
                    when (gesture) {
                        Gesture.Drag -> drag(event.y)

                        // The finger moved — this is no longer a tap on the dot.
                        Gesture.Preset -> if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) gesture = Gesture.None

                        Gesture.None -> return false
                    }
                }

                MotionEvent.ACTION_UP -> {
                    when (gesture) {
                        Gesture.Drag -> {
                            performClick() // for accessibility services
                            finishInteraction()
                        }

                        Gesture.Preset -> {
                            jumpToPreset()
                        }

                        Gesture.None -> {
                            return false
                        }
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

        /** What a touch at point (x, y) of this view does: drags the ring, jumps via the dot, or is not ours. */
        private fun classify(
            x: Float,
            y: Float,
        ): Gesture {
            val dx = x / dp - width / dp / 2f
            if (ZoomZones.hitsRing(dx, y / dp - ringY)) return Gesture.Drag

            // The preset dot exists only in the collapsed state (it is not shown on the expanded scale).
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
            if (position < SNAP_TO_MIN_POSITION) position = 0.0 // near the bottom we "snap" exactly to the minimum (1x)

            val newValue = valueAt(position)
            if (abs(newValue - current) < 1e-4) return

            current = newValue
            updateTailSide(animated = false) // while the scale is expanded the tail is not visible — flip it instantly
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

        /** Tap on the bright end dot: at low zoom — to 2x, at high zoom — back to the minimum. */
        private fun jumpToPreset() {
            val target = if (tailTarget < 0.5f) minOf(maximum, TAIL_FLIP_ZOOM) else minimum
            val from = current
            if (abs(target - from) < 1e-3) return

            jumpAnimator?.cancel()
            jumpAnimator =
                ValueAnimator.ofFloat(0f, 1f).apply {
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

            expandAnimator =
                ValueAnimator.ofFloat(expand, target).apply {
                    duration = if (target > expand) EXPAND_MS else COLLAPSE_MS
                    interpolator = DecelerateInterpolator(1.5f) // ≈ cubic deceleration (CubicOut)
                    addUpdateListener {
                        expand = it.animatedValue as Float
                        invalidate()
                    }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (expand <= 0f && expandedReported) {
                                    expandedReported = false
                                    onExpandedChanged?.invoke(false)
                                }
                            }
                        },
                    )
                    start()
                }
        }

        /**
         * Which side of the ring the dotted tail is on. The threshold is 2x (as in the Huawei camera: 1.9x is still "up", 2.0x already "down"); if the camera
         * does not reach 2x, the middle of the scale is used so that the tail can still flip.
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

            tailAnimator =
                ValueAnimator.ofFloat(tailSide, target).apply {
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
        // Drawing
        // ------------------------------------------------------------------

        override fun onDraw(canvas: Canvas) {
            val widthDp = width / dp
            if (widthDp <= 0 || heightDp <= 2 * TRACK_INSET) return

            val cx = widthDp / 2f
            val expand = expand
            val ringY = ringY

            if (expand > 0.01f) {
                // A dotted line along the whole scale (no dots under the ring); the ends are brighter.
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

                // Labels beyond the ends of the scale; hidden when the ring comes right up to them (as in Huawei).
                drawEndLabel(canvas, format(maximum), cx, top - LABEL_OFFSET, ringY, expand)
                drawEndLabel(canvas, format(minimum), cx, bottom + LABEL_OFFSET, ringY, expand)
            }

            if (expand < 0.99f) {
                // The dotted "tail": three faint dots and a bright end one — a hint where the ring can be dragged (and where to tap
                // for a jump). It flips from side to side at the zoom threshold; during the transition both sides blend.
                val visible = 1f - expand
                drawTail(canvas, cx, idleRingY, -1f, visible * (1f - tailSide))
                drawTail(canvas, cx, idleRingY, +1f, visible * tailSide)
            }

            // The ring: transparent inside (no fill), only a white outline.
            whiteWithAlpha(strokePaint, 1f)
            canvas.drawCircle(cx * dp, ringY * dp, RING_RADIUS * dp, strokePaint)

            if (fingerDown) {
                // While a finger is on the ring it shows a dot; the value is seen on the big number in the middle of the screen.
                whiteWithAlpha(fillPaint, 1f)
                canvas.drawCircle(cx * dp, ringY * dp, 2.6f * dp, fillPaint)
            } else {
                whiteWithAlpha(textPaint, 1f)
                drawCentered(canvas, format(current), cx, ringY)
            }
        }

        private fun drawEndLabel(
            canvas: Canvas,
            text: String,
            cx: Float,
            y: Float,
            ringY: Float,
            expand: Float,
        ) {
            val fade = ((abs(ringY - y) - RING_RADIUS) / 14f).coerceIn(0f, 1f)
            if (fade <= 0.01f) return

            whiteWithAlpha(textPaint, expand * fade)
            drawCentered(canvas, text, cx, y)
        }

        /**
         * Dotted tail from the ring: [direction] −1 — up, +1 — down. Sizes measured on the Huawei camera: step 6 dp, the first dot
         * 6 dp from the ring's edge; three faint dots (the closer to the ring, the fainter) and a bright end one.
         */
        private fun drawTail(
            canvas: Canvas,
            cx: Float,
            ringY: Float,
            direction: Float,
            strength: Float,
        ) {
            if (strength <= 0.01f) return

            for (i in 1..TAIL_DOTS) {
                val y = ringY + direction * (RING_RADIUS + i * TAIL_STEP)
                val last = i == TAIL_DOTS
                val alpha = if (last) 1f else 0.15f + 0.12f * i
                whiteWithAlpha(fillPaint, alpha * strength)
                canvas.drawCircle(cx * dp, y * dp, (if (last) 1.7f else 1.0f) * dp, fillPaint)
            }
        }

        /** White colour with the given opacity (colour first — it resets the alpha). */
        private fun whiteWithAlpha(
            paint: Paint,
            alpha: Float,
        ) {
            paint.color = Color.WHITE
            paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        }

        /** Text centred at point (cx, cy), coordinates in dp. */
        private fun drawCentered(
            canvas: Canvas,
            text: String,
            cx: Float,
            cy: Float,
        ) {
            val metrics = textPaint.fontMetrics
            canvas.drawText(text, cx * dp, cy * dp - (metrics.ascent + metrics.descent) / 2f, textPaint)
        }

        companion object {
            /** Inset of the scale's end from the top/bottom of the view (dp): room for the labels and for the tail under the ring in the slot. */
            const val TRACK_INSET = 52f

            private const val RING_RADIUS = 16.5f
            private const val TAIL_STEP = 6f // tail step near the ring, dp
            private const val TAIL_DOTS = 4 // three faint + a bright end one
            private const val SLOT_GAP = RING_RADIUS + TAIL_STEP * TAIL_DOTS // distance between the slots = to the end dot
            private const val TAIL_FLIP_ZOOM = 2.0 // from this zoom the ring is in the upper slot and the tail goes down
            private const val DOT_SPACING = 6f
            private const val LABEL_OFFSET = 15f // the "10x"/"1x" label beyond the end of the scale
            private const val SNAP_TO_MIN_POSITION = 0.012

            private const val COLLAPSE_DELAY_MS = 1000L // how long the scale stays after release (measured in Huawei)
            private const val EXPAND_MS = 180L
            private const val COLLAPSE_MS = 380L
            private const val SLOT_SWAP_MS = 220L
            private const val JUMP_MS = 250L

            private var formatter: DecimalFormat? = null
            private var bigFormatter: DecimalFormat? = null
            private var formatterLocale: Locale? = null

            /** The formatter cache is reset if the device language has changed. UI thread only. */
            private fun currentLocale(): Locale {
                val locale = Locale.getDefault()
                if (formatterLocale != locale) {
                    formatter = null
                    bigFormatter = null
                    formatterLocale = locale
                }
                return locale
            }

            private fun newFormat(
                pattern: String,
                locale: Locale,
            ) = DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale)).apply { roundingMode = RoundingMode.HALF_UP }

            /** Magnification text without "x", honouring the device language: "1.5" or "2". UI thread only. */
            fun formatNumber(zoom: Double): String {
                val locale = currentLocale()
                return (formatter ?: newFormat("0.#", locale).also { formatter = it }).format(zoom)
            }

            /** The big number in the middle of the screen always has a decimal digit, as in Huawei ("2.0"). UI thread only. */
            fun formatBigNumber(zoom: Double): String {
                val locale = currentLocale()
                return (bigFormatter ?: newFormat("0.0", locale).also { bigFormatter = it }).format(zoom)
            }

            fun format(zoom: Double): String = formatNumber(zoom) + "x"
        }
    }
