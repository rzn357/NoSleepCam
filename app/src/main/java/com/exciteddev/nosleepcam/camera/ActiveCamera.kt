package com.exciteddev.nosleepcam.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.view.Surface

/** Everything a request needs; exists only while the camera is fully set up (see [SessionHost.activeCamera]). */
internal class ActiveCamera(
    val device: CameraDevice,
    val session: CameraCaptureSession,
    val surface: Surface,
    val spec: CameraSpec,
    val resolution: CameraResolution,
    /** The session is a constrained high-speed one: no single requests, triggers, flash, focus regions or photos. */
    val highSpeed: Boolean,
)

/**
 * What the parts of the camera layer (tap focus, still capture) need from the controller that owns them.
 * Everything is called on the camera thread.
 */
internal interface SessionHost {
    /** The running camera, or null while it is closed or being reconfigured. */
    fun activeCamera(): ActiveCamera?

    /** The sensor area shown at the current zoom. */
    fun cropForZoom(spec: CameraSpec): SensorRect

    /** A preview-template request with the current settings and the given AF trigger. */
    fun previewRequest(
        camera: ActiveCamera,
        afTrigger: Int,
    ): CaptureRequest

    /** A still-capture request writing into [target], with the current settings. */
    fun stillRequest(
        camera: ActiveCamera,
        target: Surface,
        jpegOrientation: Int,
    ): CaptureRequest

    /** Callback for single (non-repeating) requests; it feeds the controller's per-frame bookkeeping. */
    val captureCallback: CameraCaptureSession.CaptureCallback

    /** Re-sends the repeating preview request with the current settings. */
    fun applyRepeating()
}
