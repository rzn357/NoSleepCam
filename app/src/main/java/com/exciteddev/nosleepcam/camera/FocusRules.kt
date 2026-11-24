package com.exciteddev.nosleepcam.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult

internal enum class FocusPhase {
    /** No tap focus in progress. */
    None,

    /** The tap was sent; waiting for the frame in which the START trigger was actually applied. */
    Triggering,

    /** The lens is scanning; waiting for the camera to report a locked result. */
    Searching,
}

/** Where a tap-focus attempt is: the phase and the frame in which the START trigger was applied. */
internal data class FocusState(
    val phase: FocusPhase = FocusPhase.None,
    val startFrame: Long = 0,
)

/** The parts of one capture result that matter for focusing. A null means the result did not contain the value. */
internal class FocusFrame(
    val frameNumber: Long,
    val requestTrigger: Int?,
    val afState: Int?,
)

/** The new [state] after a frame; [success] is null while the lens is still searching, else whether it focused. */
internal class FocusStep(
    val state: FocusState,
    val success: Boolean? = null,
)

/** What a tap on the picture should do for a given camera. */
internal sealed interface TapPlan {
    /** Nothing to do (high-speed session, or a camera with neither autofocus nor exposure regions). */
    data object Ignore : TapPlan

    /** A camera without autofocus (usually the front one): only the exposure is metered at the point. */
    data object Meter : TapPlan

    /** Run an autofocus scan; [afRegion]/[aeRegion] tell which regions the camera lets us set. */
    data class Focus(
        val afRegion: Boolean,
        val aeRegion: Boolean,
        val autoMode: Boolean,
    ) : TapPlan
}

/**
 * The decisions of tap-to-focus, free of Camera2 objects so they can be unit-tested.
 *
 * The Honor 10 is the reason this exists. In CONTINUOUS_PICTURE its driver answers an AF_TRIGGER_START by only "locking" the
 * current lens position after ~4 frames, without scanning anything, so a tap would not focus. In AF_MODE_AUTO the same
 * trigger starts a real scan over the chosen region. Hence [planTap] asks for [TapPlan.Focus.autoMode] whenever the camera
 * supports AUTO, and [afModeFor] picks the mode for every request while the tap is active.
 */
internal object FocusRules {
    fun planTap(
        afMode: Int,
        hasAfAuto: Boolean,
        maxAfRegions: Int,
        maxAeRegions: Int,
        highSpeed: Boolean,
    ): TapPlan {
        val noAf = afMode == CameraMetadata.CONTROL_AF_MODE_OFF
        return when {
            highSpeed || (noAf && maxAeRegions == 0) -> TapPlan.Ignore
            noAf -> TapPlan.Meter
            else -> TapPlan.Focus(afRegion = maxAfRegions > 0, aeRegion = maxAeRegions > 0, autoMode = hasAfAuto)
        }
    }

    /** The AF mode of every request: AUTO while a tap holds the lens (see above), otherwise the camera's continuous mode. */
    fun afModeFor(
        autoMode: Boolean,
        cameraAfMode: Int,
    ): Int = if (autoMode) CameraMetadata.CONTROL_AF_MODE_AUTO else cameraAfMode

    /** Advances the tap-focus state machine by one capture result. */
    fun onFrame(
        state: FocusState,
        frame: FocusFrame,
    ): FocusStep {
        if (state.phase == FocusPhase.None) return FocusStep(state)

        var current = state
        if (current.phase == FocusPhase.Triggering) {
            // Frames sent before the trigger still show the old AF state; wait for the one that carries START.
            if (frame.requestTrigger != CameraMetadata.CONTROL_AF_TRIGGER_START) return FocusStep(current)

            current = FocusState(FocusPhase.Searching, frame.frameNumber)
        }

        if (frame.frameNumber < current.startFrame) return FocusStep(current)

        return when (frame.afState) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> FocusStep(FocusState(startFrame = current.startFrame), success = true)
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> FocusStep(FocusState(startFrame = current.startFrame), success = false)
            else -> FocusStep(current)
        }
    }
}
