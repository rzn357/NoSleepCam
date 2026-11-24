package com.exciteddev.nosleepcam.camera

/**
 * Measures the actual frame rate from sensor timestamps (more accurate than from callback arrival times),
 * over windows of about [windowNs] nanoseconds. Not thread-safe: call it from one thread.
 */
internal class FrameRateMeter(
    private val windowNs: Long = ONE_SECOND_NS,
) {
    private var firstTimestamp = 0L
    private var count = 0

    /** Feeds the sensor timestamp of one frame; returns the frame rate when a window has just been completed, else null. */
    fun onFrame(timestampNs: Long): Double? {
        if (timestampNs <= 0) return null

        if (count == 0) firstTimestamp = timestampNs

        count++
        val span = timestampNs - firstTimestamp
        if (span < windowNs) return null

        val fps = (count - 1) * ONE_SECOND_NS.toDouble() / span
        count = 0
        return fps
    }

    /** The camera was closed or reconfigured: start a new window. */
    fun reset() {
        count = 0
    }

    private companion object {
        const val ONE_SECOND_NS = 1_000_000_000L
    }
}
