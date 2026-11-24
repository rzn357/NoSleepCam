package com.exciteddev.nosleepcam.camera

/**
 * The zoom the user asked for and the limits it has to respect.
 *
 * The spec offers a maximum that may exceed what the camera declares (the Huawei/Honor back camera reports 6x and delivers 10x).
 * If a frame shows that the camera silently clamps the requested zoom, [verify] lowers that maximum to what is really achievable,
 * so that the slider does not promise more than there is. The spec itself stays as the system reported it.
 */
internal class ZoomControl {
    /** The zoom the user asked for: written from the UI thread, read on the camera thread. */
    @Volatile var requested = 1f

    private val limits = HashMap<String, Float>() // camera id → the zoom it really delivers (lower than offered); camera thread only

    /** The maximum offered for this camera outside high-speed mode. */
    fun offeredMax(spec: CameraSpec): Float = limits[spec.id] ?: spec.offeredMaxZoom

    /** The maximum offered at the moment: in high-speed mode — only the one declared by the camera. */
    fun maxZoom(
        spec: CameraSpec,
        highSpeed: Boolean,
    ): Float = if (highSpeed) spec.declaredMaxZoom else offeredMax(spec)

    fun current(
        spec: CameraSpec,
        highSpeed: Boolean,
    ): Float = requested.coerceIn(1f, maxOf(1f, maxZoom(spec, highSpeed)))

    /** The sensor area that must be shown at the current zoom (centred). */
    fun crop(
        spec: CameraSpec,
        highSpeed: Boolean,
    ): SensorRect = SensorGeometry.cropForZoom(spec.activeArray, current(spec, highSpeed))

    /**
     * Compares the crop width that was asked for with the one the camera applied (from a completed frame). If the camera did not
     * deliver the offered zoom, lowers the limit, pulls the requested zoom down to it and returns the new limit; otherwise null.
     * Not for high-speed frames: there the crop region may not apply, which is not zoom "clamping".
     *
     * A driver hang cannot be detected this way (the camera thread blocks inside the driver call), so zoom never goes beyond
     * the limits verified on the device in the first place.
     */
    fun verify(
        spec: CameraSpec,
        askedWidth: Int,
        appliedWidth: Int,
    ): Float? {
        val offered = offeredMax(spec)
        if (offered <= spec.declaredMaxZoom + WITHIN_DECLARED_EPSILON) return null // we request within the declared range
        if (!SensorGeometry.isCropClamped(askedWidth, appliedWidth)) return null

        val limit = SensorGeometry.achievedZoom(spec.activeArray.width, appliedWidth, spec.declaredMaxZoom)
        if (limit >= offered - LOWER_ONLY_IF_BELOW) return null

        limits[spec.id] = limit
        if (requested > limit) requested = limit
        return limit
    }

    private companion object {
        const val WITHIN_DECLARED_EPSILON = 0.01f
        const val LOWER_ONLY_IF_BELOW = 0.05f
    }
}
