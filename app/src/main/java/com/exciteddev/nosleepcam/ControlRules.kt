package com.exciteddev.nosleepcam

/** Decisions about what a tap on a control should do, kept free of Android classes so they can be unit-tested. */
internal object ControlRules {
    enum class FlashClick {
        /** Switch the torch on or off. */
        Toggle,

        /** This camera has no flash: explain why nothing happens. */
        NoFlash,

        /** A high-speed session has no flash. */
        HighSpeed,
    }

    /** [supportsFlash] is null until the camera has reported its capabilities. */
    fun flashClick(
        supportsFlash: Boolean?,
        highSpeed: Boolean,
    ): FlashClick =
        when {
            supportsFlash == false -> FlashClick.NoFlash
            highSpeed -> FlashClick.HighSpeed
            else -> FlashClick.Toggle
        }

    enum class PhotoRequest {
        Take,

        /** A high-speed session has no JPEG stream. */
        HighSpeed,

        /** The camera has not reported its capabilities yet: ignore the tap silently. */
        CameraNotReady,

        /** The camera rejected the preview + JPEG combination for this resolution. */
        Unavailable,

        /** Before Android 10 the gallery write needs the storage permission. */
        NeedsStoragePermission,
    }

    fun photoRequest(
        highSpeed: Boolean,
        cameraReady: Boolean,
        canCapture: Boolean,
        needsStoragePermission: Boolean,
    ): PhotoRequest =
        when {
            highSpeed -> PhotoRequest.HighSpeed
            !cameraReady -> PhotoRequest.CameraNotReady
            !canCapture -> PhotoRequest.Unavailable
            needsStoragePermission -> PhotoRequest.NeedsStoragePermission
            else -> PhotoRequest.Take
        }
}
