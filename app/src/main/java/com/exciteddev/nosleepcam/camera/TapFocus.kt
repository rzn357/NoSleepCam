package com.exciteddev.nosleepcam.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.util.Log
import com.exciteddev.nosleepcam.LOG_TAG
import com.exciteddev.nosleepcam.StartupTrace

/**
 * Tap to focus: the regions the user picked, the AUTO-mode switch, the trigger sequence and the wait for the result.
 * Runs on the camera thread ([handler]) like the controller that owns it; the decisions themselves are in [FocusRules].
 *
 * A photo taken right after a tap waits for the focus to settle ([whenSettled]) so that it does not come out blurred.
 */
internal class TapFocus(
    private val handler: Handler,
    private val events: CameraEvents,
    private val host: SessionHost,
) {
    private var state = FocusState()
    private var token = 0
    private var pending: (() -> Unit)? = null
    private val flushRunnable = Runnable { flushPending() }
    private var traceLastAfState = -1

    /**
     * While true, all requests go in AF_MODE_AUTO — the Honor 10 driver only scans in that mode (see [FocusRules]).
     * It stays on until the lock is released, so a photo taken after the tap keeps the focused lens position.
     */
    var autoMode = false
        private set

    /** Where the user tapped, in sensor coordinates; kept after the lock ends so that continuous AF keeps favouring that area. */
    var afRegion: SensorRect? = null
        private set
    var aeRegion: SensorRect? = null
        private set

    /** Focus at point (s, t) ∈ [0,1]² of the "natural" (as on a phone in portrait) image. */
    fun tap(
        s: Float,
        t: Float,
    ) {
        val camera = host.activeCamera()
        val plan = camera?.let { planFor(it) } ?: TapPlan.Ignore
        if (camera == null || plan == TapPlan.Ignore) {
            events.emit { it.onFocus(FocusStatus.Failed) } // remove the indicator that is already shown
            return
        }

        val spec = camera.spec
        val visible = SensorGeometry.visibleRect(host.cropForZoom(spec), camera.resolution.width, camera.resolution.height)
        val region = SensorGeometry.focusRegion(s, t, spec.sensorOrientation, visible, spec.activeArray)

        if (plan is TapPlan.Focus) {
            afRegion = region.takeIf { plan.afRegion }
            aeRegion = region.takeIf { plan.aeRegion }
            start(camera, plan, s, t)
        } else {
            // A camera without autofocus (usually the front one): at this point we only meter the exposure.
            afRegion = null
            aeRegion = region
            host.applyRepeating()
            events.emit { it.onFocus(FocusStatus.Metered) }
        }
    }

    private fun planFor(camera: ActiveCamera): TapPlan {
        val spec = camera.spec
        return FocusRules.planTap(spec.afMode, spec.hasAfAuto, spec.maxAfRegions, spec.maxAeRegions, camera.highSpeed)
    }

    private fun start(
        camera: ActiveCamera,
        plan: TapPlan.Focus,
        s: Float,
        t: Float,
    ) {
        val myToken = ++token
        state = FocusState(FocusPhase.Triggering)
        autoMode = plan.autoMode
        events.emit { it.onFocus(FocusStatus.Searching) }
        StartupTrace.req {
            "TAP s=${"%.2f".format(s)} t=${"%.2f".format(t)} -> sensor region=$afRegion afMode=${camera.spec.afMode} plan=$plan"
        }

        try {
            // Reset the previous trigger, then start a new one and update the regions in the repeating request.
            val session = camera.session
            val callback = host.captureCallback
            session.capture(host.previewRequest(camera, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL), callback, handler)
            session.capture(host.previewRequest(camera, CameraMetadata.CONTROL_AF_TRIGGER_START), callback, handler)
            session.setRepeatingRequest(host.previewRequest(camera, CameraMetadata.CONTROL_AF_TRIGGER_IDLE), callback, handler)
        } catch (ex: Exception) {
            Log.w(LOG_TAG, "startFocus: ${ex.message}")
            state = FocusState()
            return
        }

        handler.postDelayed({
            if (myToken == token && state.phase != FocusPhase.None) {
                events.emit { it.onFocus(FocusStatus.Failed) }
                endLock()
            }
        }, FOCUS_TIMEOUT_MS)
    }

    /** Sends a single AF trigger (START for a camera whose only mode is AUTO and does not focus on its own, CANCEL to release a lock). */
    fun triggerAf(trigger: Int) {
        val camera = host.activeCamera() ?: return
        if (camera.highSpeed) return // a high-speed session does not accept single requests and triggers

        try {
            camera.session.capture(host.previewRequest(camera, trigger), host.captureCallback, handler)
        } catch (ex: Exception) {
            Log.w(LOG_TAG, "triggerAf($trigger): ${ex.message}")
        }
    }

    /** A new session replaces the old one: a tap that was in flight on it will never finish. */
    fun onSessionReplaced() {
        state = FocusState()
    }

    /** The camera is closing: forget everything that was in flight, including a photo that was waiting. */
    fun cancel() {
        token++
        state = FocusState()
        autoMode = false
        pending = null
        handler.removeCallbacks(flushRunnable)
    }

    /** Runs [action] now if no tap focus is in progress, otherwise as soon as it finishes (or after a timeout). */
    fun whenSettled(action: () -> Unit) {
        if (state.phase == FocusPhase.None) {
            action()
            return
        }

        pending = action // a newer waiting action replaces the older one
        handler.removeCallbacks(flushRunnable)
        handler.postDelayed(flushRunnable, WAIT_FOR_FOCUS_MS)
    }

    private fun flushPending() {
        handler.removeCallbacks(flushRunnable)
        val action = pending ?: return

        pending = null
        action()
    }

    /** Called for every completed frame of the session. */
    fun onFrame(
        request: CaptureRequest,
        result: TotalCaptureResult,
    ) {
        trace(request, result)
        if (state.phase == FocusPhase.None) return

        val frame =
            FocusFrame(result.frameNumber, request.get(CaptureRequest.CONTROL_AF_TRIGGER), result.get(CaptureResult.CONTROL_AF_STATE))
        val step = FocusRules.onFrame(state, frame)
        state = step.state
        step.success?.let(::onResult)
    }

    private fun onResult(success: Boolean) {
        val myToken = token
        events.emit { it.onFocus(if (success) FocusStatus.Focused else FocusStatus.Failed) }
        flushPending() // the lens is locked (AUTO mode) — we take the photo that was waiting for focus right now

        // Briefly hold the lock, then return to continuous autofocus (the region stays).
        handler.postDelayed({
            if (myToken == token) endLock()
        }, FOCUS_HOLD_MS)
    }

    private fun endLock() {
        token++
        state = FocusState()
        flushPending()
        autoMode = false
        triggerAf(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        host.applyRepeating()
    }

    /** AF diagnostics (tag NSC-Req): state, mode, request trigger, regions in the response, lens distance. */
    private fun trace(
        request: CaptureRequest,
        result: TotalCaptureResult,
    ) = StartupTrace.req {
        val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: -1
        val changed = afState != traceLastAfState
        traceLastAfState = afState
        if (!changed && state.phase == FocusPhase.None) return@req ""
        val regions = result.get(CaptureResult.CONTROL_AF_REGIONS)?.firstOrNull()?.rect
        "AF frame=${result.frameNumber} state=$afState mode=${result.get(CaptureResult.CONTROL_AF_MODE)} " +
            "reqTrigger=${request.get(CaptureRequest.CONTROL_AF_TRIGGER)} phase=${state.phase} " +
            "focusDist=${result.get(CaptureResult.LENS_FOCUS_DISTANCE)} regionInResult=$regions"
    }

    private companion object {
        const val FOCUS_HOLD_MS = 1200L // how long to hold the focus lock after a touch
        const val FOCUS_TIMEOUT_MS = 4000L // maximum wait for the focus result
        const val WAIT_FOR_FOCUS_MS = 1500L // how long a photo waits for tap focus
    }
}
