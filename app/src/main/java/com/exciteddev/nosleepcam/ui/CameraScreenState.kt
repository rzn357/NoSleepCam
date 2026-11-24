package com.exciteddev.nosleepcam.ui

import com.exciteddev.nosleepcam.camera.CameraCapabilities
import com.exciteddev.nosleepcam.camera.CameraResolution
import com.exciteddev.nosleepcam.camera.CameraStatus

/**
 * Everything the camera screen shows and the user has chosen, as one immutable value. The activity only replaces it
 * (`state = state.with…`) and then renders the difference: data flows one way, from events through the state to the views and
 * the camera, with no view-model or library involved.
 *
 * The transitions that carry decisions are methods here, free of Android classes, so they can be unit-tested.
 */
internal data class CameraScreenState(
    /** What the open camera can do; null until it has reported. */
    val capabilities: CameraCapabilities? = null,
    /** The resolution the camera actually runs with (not shown while it is in high-speed mode). */
    val appliedResolution: CameraResolution? = null,
    val selectedCameraId: String? = null,
    /** A problem the camera reported; null — all is well. */
    val cameraStatus: CameraStatus? = null,
    /** A short message that takes the place of [cameraStatus] for a moment ("this camera has no flash"). */
    val transientMessage: String? = null,
    val permissionNeeded: Boolean = false,
    /** Measured frame rate; 0 — not running. */
    val frameRate: Int = 0,
    val zoom: Double = 1.0,
    val flashOn: Boolean = false,
    /** High-speed mode; deliberately not persisted between launches. */
    val highSpeed: Boolean = false,
    val settingsOpen: Boolean = false,
) {
    /**
     * The camera reported its capabilities (it may be a different camera, or the same one in another mode).
     * The zoom is kept within the camera's range, and high-speed mode is dropped if this camera no longer offers it.
     */
    fun withCapabilities(caps: CameraCapabilities): CameraScreenState =
        copy(
            capabilities = caps,
            selectedCameraId = caps.camera.id,
            zoom = zoom.coerceIn(caps.minZoom, maxOf(caps.minZoom, caps.maxZoom)),
            highSpeed = highSpeed && caps.highSpeedFps > 0,
        )

    /** A high-speed session has no flash (its request does not contain it), so turning the mode on turns the flash off. */
    fun withHighSpeed(on: Boolean): CameraScreenState = copy(highSpeed = on, flashOn = flashOn && !on)

    /** What the settings panel shows; [pageHeightDp] is the height of the content area, used to size the resolution list. */
    fun settingsUiState(pageHeightDp: Double): SettingsUiState =
        SettingsUiState(
            resolutions = capabilities?.resolutions.orEmpty(),
            frameRates = capabilities?.frameRates.orEmpty(),
            selectedResolution = appliedResolution,
            cameras = capabilities?.availableCameras.orEmpty(),
            selectedCameraId = selectedCameraId,
            pageHeightDp = pageHeightDp,
            highSpeed =
                capabilities
                    ?.takeIf { it.highSpeedFps > 0 }
                    ?.let { HighSpeedState(it.highSpeedFps, it.highSpeedResolution, highSpeed) },
        )
}
