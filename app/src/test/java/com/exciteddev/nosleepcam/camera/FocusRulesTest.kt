package com.exciteddev.nosleepcam.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusRulesTest {
    private val continuous = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
    private val off = CameraMetadata.CONTROL_AF_MODE_OFF
    private val start = CameraMetadata.CONTROL_AF_TRIGGER_START
    private val idle = CameraMetadata.CONTROL_AF_TRIGGER_IDLE

    // ---- planTap ----

    @Test
    fun aCameraThatSupportsAutoModeFocusesInAutoMode() {
        // The Honor 10 case: continuous mode only "locks" the lens on a trigger, so the tap must switch to AUTO to get a scan.
        val plan = FocusRules.planTap(continuous, hasAfAuto = true, maxAfRegions = 1, maxAeRegions = 1, highSpeed = false)
        assertEquals(TapPlan.Focus(afRegion = true, aeRegion = true, autoMode = true), plan)
    }

    @Test
    fun aCameraWithoutAutoModeStaysInItsOwnMode() {
        val plan = FocusRules.planTap(continuous, hasAfAuto = false, maxAfRegions = 1, maxAeRegions = 1, highSpeed = false)
        assertEquals(TapPlan.Focus(afRegion = true, aeRegion = true, autoMode = false), plan)
    }

    @Test
    fun regionsAreOnlyUsedWhenTheCameraHasThem() {
        val plan = FocusRules.planTap(continuous, hasAfAuto = true, maxAfRegions = 0, maxAeRegions = 1, highSpeed = false)
        assertEquals(TapPlan.Focus(afRegion = false, aeRegion = true, autoMode = true), plan)
    }

    @Test
    fun aCameraWithoutAutofocusOnlyMetersExposure() {
        assertEquals(TapPlan.Meter, FocusRules.planTap(off, hasAfAuto = false, maxAfRegions = 0, maxAeRegions = 1, highSpeed = false))
    }

    @Test
    fun aTapDoesNothingWithoutAutofocusAndWithoutExposureRegions() {
        assertEquals(TapPlan.Ignore, FocusRules.planTap(off, hasAfAuto = false, maxAfRegions = 0, maxAeRegions = 0, highSpeed = false))
    }

    @Test
    fun aTapDoesNothingInHighSpeedMode() {
        assertEquals(TapPlan.Ignore, FocusRules.planTap(continuous, hasAfAuto = true, maxAfRegions = 1, maxAeRegions = 1, highSpeed = true))
    }

    // ---- afModeFor ----

    @Test
    fun requestsUseAutoModeOnlyWhileATapHoldsTheLens() {
        assertEquals(CameraMetadata.CONTROL_AF_MODE_AUTO, FocusRules.afModeFor(autoMode = true, cameraAfMode = continuous))
        assertEquals(continuous, FocusRules.afModeFor(autoMode = false, cameraAfMode = continuous))
    }

    // ---- onFrame ----

    private fun frame(
        number: Long,
        trigger: Int? = idle,
        state: Int? = null,
    ) = FocusFrame(number, trigger, state)

    private val triggering = FocusState(FocusPhase.Triggering)

    @Test
    fun anIdleStateIgnoresEveryFrame() {
        val step = FocusRules.onFrame(FocusState(), frame(10, start, CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED))
        assertEquals(FocusPhase.None, step.state.phase)
        assertNull(step.success)
    }

    @Test
    fun aLockedStateBeforeTheStartTriggerIsNotTheResult() {
        // Continuous AF may already report a locked state in frames sent before our trigger took effect.
        val step = FocusRules.onFrame(triggering, frame(5, idle, CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED))
        assertEquals(FocusPhase.Triggering, step.state.phase)
        assertNull(step.success)
    }

    @Test
    fun theFrameWithTheStartTriggerBeginsTheSearch() {
        val step = FocusRules.onFrame(triggering, frame(7, start, CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN))
        assertEquals(FocusState(FocusPhase.Searching, startFrame = 7), step.state)
        assertNull(step.success)
    }

    @Test
    fun theSearchKeepsGoingWhileTheLensScans() {
        val searching = FocusState(FocusPhase.Searching, startFrame = 7)
        val step = FocusRules.onFrame(searching, frame(9, idle, CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN))
        assertEquals(searching, step.state)
        assertNull(step.success)
    }

    @Test
    fun aLockedFrameEndsTheSearchWithSuccess() {
        val searching = FocusState(FocusPhase.Searching, startFrame = 7)
        val step = FocusRules.onFrame(searching, frame(12, idle, CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED))
        assertEquals(FocusPhase.None, step.state.phase)
        assertEquals(true, step.success)
    }

    @Test
    fun aNotFocusedLockEndsTheSearchWithFailure() {
        val searching = FocusState(FocusPhase.Searching, startFrame = 7)
        val step = FocusRules.onFrame(searching, frame(12, idle, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED))
        assertEquals(FocusPhase.None, step.state.phase)
        assertEquals(false, step.success)
    }

    @Test
    fun theStartFrameItselfCanAlreadyCarryTheResult() {
        val step = FocusRules.onFrame(triggering, frame(7, start, CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED))
        assertEquals(FocusPhase.None, step.state.phase)
        assertEquals(true, step.success)
    }

    @Test
    fun aLateFrameFromBeforeTheTriggerIsIgnored() {
        val searching = FocusState(FocusPhase.Searching, startFrame = 7)
        val step = FocusRules.onFrame(searching, frame(6, idle, CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED))
        assertEquals(searching, step.state)
        assertNull(step.success)
    }

    @Test
    fun aFrameWithoutAnAfStateChangesNothing() {
        val searching = FocusState(FocusPhase.Searching, startFrame = 7)
        val step = FocusRules.onFrame(searching, frame(9, idle, null))
        assertTrue(step.state == searching && step.success == null)
    }
}
