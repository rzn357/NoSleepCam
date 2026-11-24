package com.exciteddev.nosleepcam.camera

/**
 * High-speed mode (up to 120 fps) as the user wants it and as the device has allowed so far: sizes the device rejected are
 * not tried again, and a camera that rejected all of them is not offered the mode any more.
 */
internal class HighSpeedPolicy {
    /** The user turned the mode on: written from the UI thread, read on the camera thread. */
    @Volatile var wanted = false

    private val rejectedSizes = HashSet<Pair<String, CameraResolution>>() // camera thread only
    private val brokenCameras = HashSet<String>()

    /** The camera declares the mode and the device has not rejected all of it. */
    fun isSupported(spec: CameraSpec) = spec.hasHighSpeed && spec.id !in brokenCameras

    /** The session should be a high-speed one. */
    fun isActive(spec: CameraSpec) = wanted && isSupported(spec)

    /** The first high-speed size the device has not yet rejected (the largest — it is also the best); null — none left. */
    fun config(spec: CameraSpec): HighSpeedConfig? = spec.highSpeedConfigs.firstOrNull { (spec.id to it.size) !in rejectedSizes }

    /** The device rejected a session of this [size]. Returns true if no size is left for the camera (it is now [isSupported] no more). */
    fun reject(
        spec: CameraSpec,
        size: CameraResolution,
    ): Boolean {
        rejectedSizes.add(spec.id to size)
        if (config(spec) != null) return false

        brokenCameras.add(spec.id)
        return true
    }
}
