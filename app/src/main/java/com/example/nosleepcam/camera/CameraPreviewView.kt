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
 * Native view: a TextureView with the camera preview + the focus ring.
 * Fits the frame into the available area without distortion (with black bars) and rotates
 * it correctly in any screen orientation.
 */
internal class CameraPreviewView(context: Context) :
    FrameLayout(context), TextureView.SurfaceTextureListener, CameraListener {

    /** The first camera frame is shown (called on the UI thread). */
    var onFirstFrame: (() -> Unit)? = null

    /** Double tap on the screen — photo (the first tap starts focusing immediately, like a regular tap). */
    var onDoubleTapped: (() -> Unit)? = null

    // The controller is shared by the whole process: the camera may have been warmed up before this view was created.
    private val controller = CameraController.getShared(context)
    private val texture = TextureView(context)
    private val indicator: FocusIndicatorView
    private val gestures: GestureDetector
    private val displayListener = DisplayListener()
    private val transform = Matrix()
    private val inverse = Matrix()
    private val imageRect = RectF() // where on screen the camera image is (without black bars)
    private var geometry: PreviewGeometry? = null
    private var firstFrameReported = false
    private var draggingExposure = false
    private val resetExposureRunnable = Runnable { controller.setExposureCompensation(0) }

    init {
        setBackgroundColor(Color.BLACK)

        texture.surfaceTextureListener = this
        addView(texture, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // The focus and brightness indicator covers the whole view: it does not handle touches (see onTouchEvent below).
        indicator = FocusIndicatorView(context)
        indicator.onExposureChanged = { controller.setExposureCompensation(it) }
        addView(indicator, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        gestures = GestureDetector(context, TapListener())
    }

    // ---------------------------------------------------------------------
    // Lifecycle
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
        // The controller is shared, so we don't destroy it — we only unsubscribe.
        controller.removeListener(this)
        super.onDetachedFromWindow()
    }

    private fun displayManager() = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    // ---------------------------------------------------------------------
    // Controller events (camera thread → UI thread)
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
        return false // the texture is released by the controller after the camera is closed
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (firstFrameReported) return

        firstFrameReported = true
        StartupTrace.mark { "FIRST camera FRAME on screen" }
        onFirstFrame?.invoke()
    }

    // ---------------------------------------------------------------------
    // Rotation and fitting of the frame
    // ---------------------------------------------------------------------

    /** Whether a photo can be taken now (there is a JPEG stream, not high-speed mode). */
    val canCapture: Boolean get() = geometry?.canCapture == true

    /** Current screen rotation: 0/90/180/270. */
    val displayRotation: Int get() = displayRotationDegrees()

    /** Hides the focus indicator (e.g. when the touch turned out to be the first tap of a double tap). */
    fun hideIndicator() = indicator.hideNow()

    /** Frozen camera image for the capture animation; null — no frame yet. UI thread only. */
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
     * TextureView stretches the image over its whole area, so we build a matrix that:
     * 1) restores the proportions of the "natural" frame (the camera has already rotated it to the device's natural orientation),
     * 2) additionally rotates the frame by the screen rotation angle,
     * 3) fits the result into the area with black bars (no cropping).
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
        // Back camera: extra rotation against the screen; front camera — along with the screen.
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
    // Touch: focus at the chosen point
    // ---------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            // A finger on the brightness "sun" is not a touch for focus but a brightness adjustment.
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

        // Neither focus nor brightness (e.g. high-speed mode): the indicator would mean nothing.
        if (!geometry.canTap) return

        // Screen coordinates → coordinates of the "natural" frame (inverse of the fitting matrix).
        val point = floatArrayOf(x, y)
        inverse.mapPoints(point)
        val s = point[0] / texture.width
        val t = point[1] / texture.height
        if (s < 0 || s > 1 || t < 0 || t > 1) return // touch on the black bars

        indicator.showAt(x, y)
        // A new touch resets the brightness, as in the Huawei camera, but not immediately: it may be the first tap of a double tap (photo),
        // and then the photo must come out with the brightness the user has set.
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

/** Preview snapshot: [bitmap] (downscaled), [imageInBitmap] — the image area within it, [imageOnScreen] — where it is on screen. */
internal class PreviewSnapshot(val bitmap: Bitmap, val imageInBitmap: Rect, val imageOnScreen: RectF)

private const val SNAPSHOT_SCALE = 0.5f
