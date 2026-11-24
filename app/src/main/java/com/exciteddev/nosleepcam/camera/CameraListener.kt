package com.exciteddev.nosleepcam.camera

import android.net.Uri
import java.util.concurrent.CopyOnWriteArrayList

/** Geometry of the preview buffer — needed to rotate and fit the image correctly. */
internal data class PreviewGeometry(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val sensorOrientation: Int,
    val isFront: Boolean,
    val canFocus: Boolean,
    /** Range of exposure compensation indices and the step in EV; evMin == evMax — brightness adjustment is unavailable. */
    val evMin: Int = 0,
    val evMax: Int = 0,
    val evStep: Float = 0f,
    /** The session has a JPEG stream for photos (absent in high-speed mode and when the device rejected such a stream). */
    val canCapture: Boolean = false,
) {
    val isValid: Boolean get() = bufferWidth > 0 && bufferHeight > 0

    val canAdjustExposure: Boolean get() = evMax > evMin

    /** A touch on the frame makes sense: either focus, or at least exposure metering and brightness. */
    val canTap: Boolean get() = canFocus || canAdjustExposure
}

/** [Metered] — a camera without autofocus: only the exposure was metered at this point. */
internal enum class FocusStatus { Searching, Focused, Failed, Metered }

/**
 * Subscriber to camera events. All methods are called on the camera's background thread —
 * the consumer switches to the UI thread itself.
 */
internal interface CameraListener {
    fun onCapabilities(capabilities: CameraCapabilities) {}

    fun onResolutionApplied(resolution: CameraResolution) {}

    fun onGeometry(geometry: PreviewGeometry) {}

    /** A problem to show to the user; null — all is well, the message is hidden. */
    fun onStatus(status: CameraStatus?) {}

    fun onFocus(status: FocusStatus) {}

    /** Actual frame rate (fps, integer); 0 — the camera is not running or is being reconfigured. */
    fun onFrameRate(fps: Int) {}

    /** The photo has been written to the gallery. Not called on the camera thread. */
    fun onPhotoSaved(uri: Uri) {}

    /** The photo could not be taken or saved. */
    fun onPhotoFailed() {}
}

/** The subscribers of one controller, shared by the parts it is made of. Safe to use from any thread. */
internal class CameraEvents {
    private val listeners = CopyOnWriteArrayList<CameraListener>()

    fun add(listener: CameraListener) {
        listeners.add(listener)
    }

    fun remove(listener: CameraListener) {
        listeners.remove(listener)
    }

    fun emit(block: (CameraListener) -> Unit) {
        for (listener in listeners) block(listener)
    }
}
