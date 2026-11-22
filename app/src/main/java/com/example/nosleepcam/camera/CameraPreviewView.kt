package com.example.nosleepcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import com.example.nosleepcam.StartupTrace

/**
 * Нативний елемент: TextureView з попереднім переглядом камери + кільце фокусу.
 * Вписує кадр у доступну область без спотворень (з чорними полями) і правильно
 * повертає його при будь-якій орієнтації екрана.
 */
internal class CameraPreviewView(context: Context) :
    FrameLayout(context), TextureView.SurfaceTextureListener, CameraListener {

    /** Перший кадр камери показано (викликається в UI-потоці). */
    var onFirstFrame: (() -> Unit)? = null

    /** Подвійний тап по екрану — знімок (перший тап одразу запускає фокус, як і звичайний тап). */
    var onDoubleTapped: (() -> Unit)? = null

    // Контролер спільний для всього процесу: камера могла бути прогріта ще до створення цього елемента.
    private val controller = CameraController.getShared(context)
    private val texture = TextureView(context)
    private val indicator: FocusIndicatorView
    private val gestures: GestureDetector
    private val displayListener = DisplayListener()
    private val transform = Matrix()
    private val inverse = Matrix()
    private val imageRect = RectF() // де на екрані зображення камери (без чорних полів)
    private var geometry: PreviewGeometry? = null
    private var firstFrameReported = false
    private var draggingExposure = false
    private val resetExposureRunnable = Runnable { controller.setExposureCompensation(0) }

    init {
        setBackgroundColor(Color.BLACK)

        texture.surfaceTextureListener = this
        addView(texture, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Індикатор фокусу й яскравості займає весь елемент: дотики він не обробляє (див. onTouchEvent нижче).
        indicator = FocusIndicatorView(context)
        indicator.onExposureChanged = { controller.setExposureCompensation(it) }
        addView(indicator, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        gestures = GestureDetector(context, TapListener())
    }

    // ---------------------------------------------------------------------
    // Життєвий цикл
    // ---------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller.addListener(this)
        displayManager()?.registerDisplayListener(displayListener, null)
        updateTransform()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(resetExposureRunnable)
        displayManager()?.unregisterDisplayListener(displayListener)
        // Контролер спільний, тож його не знищуємо — лише відписуємось.
        controller.removeListener(this)
        super.onDetachedFromWindow()
    }

    private fun displayManager() = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    // ---------------------------------------------------------------------
    // Події контролера (потік камери → UI-потік)
    // ---------------------------------------------------------------------

    override fun onGeometry(geometry: PreviewGeometry) {
        post {
            this.geometry = geometry
            indicator.configure(geometry)
            updateTransform()
        }
    }

    override fun onFocus(status: FocusStatus) {
        post { indicator.setStatus(status) }
    }

    // ---------------------------------------------------------------------
    // TextureView.SurfaceTextureListener
    // ---------------------------------------------------------------------

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        controller.onSurfaceAvailable(surface)
        updateTransform()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = updateTransform()

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        controller.onSurfaceDestroyed(surface)
        return false // текстуру звільняє контролер після закриття камери
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (firstFrameReported) return

        firstFrameReported = true
        StartupTrace.mark { "ПЕРШИЙ КАДР камери на екрані" }
        onFirstFrame?.invoke()
    }

    // ---------------------------------------------------------------------
    // Поворот і вписування кадру
    // ---------------------------------------------------------------------

    /** Чи можна зараз зробити знімок (є JPEG-потік, не високошвидкісний режим). */
    val canCapture: Boolean get() = geometry?.canCapture == true

    /** Поточний поворот екрана: 0/90/180/270. */
    val displayRotation: Int get() = displayRotationDegrees()

    /** Ховає індикатор фокусу (напр. коли дотик виявився першим тапом подвійного). */
    fun hideIndicator() = indicator.hideNow()

    /** Застигле зображення камери для анімації знімка; null — ще нема кадра. Лише з UI-потоку. */
    fun snapshot(): PreviewSnapshot? {
        val w = texture.width
        val h = texture.height
        if (w == 0 || h == 0 || !texture.isAvailable || imageRect.isEmpty) return null

        val bitmap = texture.getBitmap((w * SNAPSHOT_SCALE).toInt(), (h * SNAPSHOT_SCALE).toInt()) ?: return null
        val inBitmap = Rect(
            Math.round(imageRect.left * SNAPSHOT_SCALE), Math.round(imageRect.top * SNAPSHOT_SCALE),
            Math.round(imageRect.right * SNAPSHOT_SCALE), Math.round(imageRect.bottom * SNAPSHOT_SCALE),
        )
        if (!inBitmap.intersect(0, 0, bitmap.width, bitmap.height) || inBitmap.isEmpty) return null
        return PreviewSnapshot(bitmap, inBitmap, RectF(imageRect))
    }

    private fun displayRotationDegrees(): Int = when (display?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /**
     * TextureView розтягує зображення на всю свою площу, тому будуємо матрицю, яка:
     * 1) повертає пропорції «природного» кадру (камера вже повернула його під натуральну орієнтацію пристрою),
     * 2) додатково повертає кадр на кут повороту екрана,
     * 3) вписує результат у область з чорними полями (без обрізання).
     */
    private fun updateTransform() {
        val geometry = geometry
        val viewW = texture.width
        val viewH = texture.height
        if (geometry == null || !geometry.isValid || viewW == 0 || viewH == 0) return

        val sensorSwapped = geometry.sensorOrientation % 180 != 0
        val contentW = (if (sensorSwapped) geometry.bufferHeight else geometry.bufferWidth).toFloat()
        val contentH = (if (sensorSwapped) geometry.bufferWidth else geometry.bufferHeight).toFloat()

        val rotation = displayRotationDegrees()
        // Задня камера: додатковий поворот проти екрана; передня — за екраном.
        val extra = if (geometry.isFront) rotation else (360 - rotation) % 360
        val swapped = extra % 180 != 0
        val rotatedW = if (swapped) contentH else contentW
        val rotatedH = if (swapped) contentW else contentH

        val scale = minOf(viewW / rotatedW, viewH / rotatedH)
        val cx = viewW / 2f
        val cy = viewH / 2f

        imageRect.set(cx - rotatedW * scale / 2f, cy - rotatedH * scale / 2f, cx + rotatedW * scale / 2f, cy + rotatedH * scale / 2f)

        transform.reset()
        transform.setScale(scale * contentW / viewW, scale * contentH / viewH, cx, cy)
        transform.postRotate(extra.toFloat(), cx, cy)
        texture.setTransform(transform)
        transform.invert(inverse)
    }

    private inner class DisplayListener : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            indicator.hideNow()
            updateTransform()
        }

        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    // ---------------------------------------------------------------------
    // Дотик: фокус у вибраній точці
    // ---------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            // Палець на «сонечку» яскравості — це не дотик для фокусу, а регулювання яскравості.
            MotionEvent.ACTION_DOWN -> if (indicator.hitSun(event.x, event.y)) {
                draggingExposure = true
                indicator.beginDrag(event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> if (draggingExposure) {
                indicator.dragTo(event.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (draggingExposure) {
                draggingExposure = false
                indicator.endDrag()
                return true
            }
        }

        if (!draggingExposure) gestures.onTouchEvent(event)
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val geometry = geometry
        if (geometry == null || !geometry.isValid || texture.width == 0 || texture.height == 0) return

        // Ні фокусу, ні яскравості (напр. високошвидкісний режим): індикатор нічого не означав би.
        if (!geometry.canTap) return

        // Екранні координати → координати «природного» кадру (зворотна матриця вписування).
        val point = floatArrayOf(x, y)
        inverse.mapPoints(point)
        val s = point[0] / texture.width
        val t = point[1] / texture.height
        if (s < 0 || s > 1 || t < 0 || t > 1) return // дотик по чорних полях

        indicator.showAt(x, y)
        // Новий дотик скидає яскравість, як у камері Huawei, але не одразу: це може бути перший тап подвійного (знімок),
        // і тоді знімок має вийти з тією яскравістю, яку виставив користувач.
        removeCallbacks(resetExposureRunnable)
        postDelayed(resetExposureRunnable, ViewConfiguration.getDoubleTapTimeout().toLong())
        controller.focusAt(s, t)
    }

    private inner class TapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            removeCallbacks(resetExposureRunnable)
            onDoubleTapped?.invoke()
            return true
        }
    }
}

/** Знімок прев'ю: [bitmap] (зменшений), [imageInBitmap] — область зображення в ньому, [imageOnScreen] — де воно на екрані. */
internal class PreviewSnapshot(val bitmap: Bitmap, val imageInBitmap: Rect, val imageOnScreen: RectF)

private const val SNAPSHOT_SCALE = 0.5f
