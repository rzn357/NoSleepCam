package com.exciteddev.nosleepcam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.view.Surface
import com.exciteddev.nosleepcam.LOG_TAG
import com.exciteddev.nosleepcam.StartupTrace
import java.util.concurrent.Executors

/**
 * Photos: a JPEG stream that lives in the same session as the preview, the still request and the hand-over of the bytes to the
 * gallery writer. Runs on the camera thread ([handler]) like the controller that owns it; only the gallery write is off it.
 */
internal class StillCapture(
    private val context: Context,
    private val handler: Handler,
    private val events: CameraEvents,
    private val host: SessionHost,
    private val focus: TapFocus,
) {
    private var reader: ImageReader? = null // stream of the active session
    private var pendingReader: ImageReader? = null // stream of the session being configured
    private val blocked = HashSet<Pair<String, CameraResolution>>() // preview sizes whose session was rejected with a JPEG stream
    private val savePool = Executors.newSingleThreadExecutor()
    private val failureCallback = FailureCb()

    /** The active session has a JPEG stream (never true in high-speed mode, which has none). */
    val hasStream: Boolean get() = reader != null

    /** Creates the JPEG receiver for a session with the preview size [resolution]; null — photos are unavailable for it. */
    fun openStream(
        spec: CameraSpec,
        resolution: CameraResolution,
    ): ImageReader? {
        if ((spec.id to resolution) in blocked) return null

        val size = PhotoMath.pickJpegSize(spec.jpegSizes, resolution) ?: return null
        val newReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        newReader.setOnImageAvailableListener(::onImageAvailable, handler)
        pendingReader?.close()
        pendingReader = newReader
        StartupTrace.req { "JPEG ${size.key} for preview ${resolution.key}" }
        return newReader
    }

    /** The session was configured: its stream (if any) replaces the previous one. */
    fun activateStream() {
        reader?.close()
        reader = pendingReader
        pendingReader = null
    }

    /** The session was not configured (or is stale): its stream is not needed. */
    fun discardPendingStream() {
        pendingReader?.close()
        pendingReader = null
    }

    /** The device rejected the preview + JPEG combination for [resolution]: do not try it again, photos are off for that size. */
    fun block(
        spec: CameraSpec,
        resolution: CameraResolution,
    ) {
        blocked.add(spec.id to resolution)
    }

    /** The camera is closing. */
    fun release() {
        reader?.close()
        reader = null
        discardPendingStream()
    }

    /**
     * Takes a photo with the same zoom, brightness, flash and focus as in the preview. [displayRotation] is the current screen
     * rotation (0/90/180/270), which determines how the photo is rotated. If a tap focus is in progress the photo waits for it.
     * The result is [CameraListener.onPhotoSaved] or [CameraListener.onPhotoFailed].
     */
    fun capture(displayRotation: Int) = focus.whenSettled { takePhoto(displayRotation) }

    private fun takePhoto(displayRotation: Int) {
        val camera = host.activeCamera()
        val stream = reader
        StartupTrace.req {
            "capturePhoto: camera=${camera != null} reader=${stream != null} hs=${camera?.highSpeed} rotation=$displayRotation"
        }
        if (camera == null || stream == null || camera.highSpeed) {
            events.emit { it.onPhotoFailed() }
            return
        }

        try {
            val spec = camera.spec
            val orientation = PhotoMath.jpegOrientation(spec.sensorOrientation, displayRotation, spec.isFront)
            camera.session.capture(host.stillRequest(camera, stream.surface, orientation), failureCallback, handler)
        } catch (ex: Exception) {
            Log.w(LOG_TAG, "capturePhoto: ${ex.message}")
            events.emit { it.onPhotoFailed() }
        }
    }

    /** The JPEG is ready: we take the bytes and hand the gallery write over to a separate thread so as not to block the camera. */
    private fun onImageAvailable(source: ImageReader) {
        val image =
            try {
                source.acquireNextImage()
            } catch (ex: Exception) {
                Log.w(LOG_TAG, "acquireNextImage: ${ex.message}")
                null
            } ?: return

        val bytes =
            try {
                val buffer = image.planes[0].buffer
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } finally {
                image.close()
            }

        val takenAt = System.currentTimeMillis()
        StartupTrace.req { "JPEG received: ${bytes.size} bytes" }
        savePool.execute {
            val uri = PhotoSaver.save(context, bytes, takenAt)
            if (uri != null) events.emit { it.onPhotoSaved(uri) } else events.emit { it.onPhotoFailed() }
        }
    }

    /** Result of the photo: success arrives via the ImageReader, and here only the error. */
    private inner class FailureCb : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            Log.w(LOG_TAG, "Still capture failed: reason ${failure.reason}")
            events.emit { it.onPhotoFailed() }
        }
    }
}
