package com.exciteddev.nosleepcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateMeterTest {
    private val frameNs = 33_333_333L // ≈ 30 fps
    private val start = 1_000_000_000L

    private fun feed(
        meter: FrameRateMeter,
        frames: IntRange,
        offset: Long = 0,
    ): List<Double> = frames.mapNotNull { meter.onFrame(offset + start + it * frameNs) }

    @Test
    fun reportsNothingUntilAWindowIsComplete() {
        val meter = FrameRateMeter()
        assertEquals(emptyList<Double>(), feed(meter, 0..30)) // 30 intervals = 0.99999 s
    }

    @Test
    fun reportsTheRateWhenAOneSecondWindowCompletes() {
        val meter = FrameRateMeter()
        val reported = feed(meter, 0..31)
        assertEquals(1, reported.size)
        assertEquals(30.0, reported.single(), 0.01)
    }

    @Test
    fun startsANewWindowAfterEachReport() {
        val meter = FrameRateMeter()
        assertEquals(1, feed(meter, 0..62).size) // the first window ended at frame 31
        assertNotNull(meter.onFrame(start + 63 * frameNs)) // the second window began at frame 32
    }

    @Test
    fun ignoresMissingTimestamps() {
        val meter = FrameRateMeter()
        assertNull(meter.onFrame(0))
        assertNull(meter.onFrame(-5))
    }

    @Test
    fun resetDiscardsAPartialWindow() {
        val meter = FrameRateMeter()
        feed(meter, 0..20)
        meter.reset()
        // The window now starts at the next frame, so it needs a full second again.
        assertEquals(emptyList<Double>(), feed(meter, 40..70))
        assertEquals(1, feed(meter, 71..72).size)
    }
}
