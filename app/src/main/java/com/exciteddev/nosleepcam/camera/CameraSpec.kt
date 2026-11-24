package com.exciteddev.nosleepcam.camera

/** Which way a camera looks. [External] cameras (USB) are treated like back cameras. */
internal enum class LensFacing { Front, Back, External }

/** A camera's high-speed mode: frame size and the allowed rate range (e.g. 1920×1080, [30,120]). */
internal data class HighSpeedConfig(
    val size: CameraResolution,
    val lower: Int,
    val upper: Int,
)

/** Everything the app needs to know about one camera, read once from its `CameraCharacteristics`. */
internal class CameraSpec(
    val id: String,
    val lens: LensFacing,
    val sensorOrientation: Int,
    val activeArray: SensorRect,
    /** Maximum digital zoom the camera declares through the standard API. */
    val declaredMaxZoom: Float,
    /** Maximum we offer the user (≥ declared); lowered if the camera does not deliver it. */
    var maxZoom: Float,
    val afMode: Int,
    /** The camera supports AUTO mode (needed for real tap focus, see CameraController.startFocus). */
    val hasAfAuto: Boolean,
    val maxAfRegions: Int,
    val maxAeRegions: Int,
    val hasFlash: Boolean,
    /** Range of exposure compensation indices (brightness) and the EV step of one index. */
    val evMin: Int,
    val evMax: Int,
    val evStep: Float,
    /** The camera is suitable for showing an image (not a depth/IR sensor etc.). */
    val backwardCompatible: Boolean,
    val resolutions: List<CameraResolution>,
    /** Sizes in which the camera delivers JPEG (photos), from the largest. */
    val jpegSizes: List<CameraResolution>,
    /** Highest frame rate per preview size, as declared by the camera. */
    val frameRates: Map<CameraResolution, Double>,
    /** Target frame rate ranges the camera allows for auto-exposure. */
    val aeFpsRanges: List<Pair<Int, Int>>,
    /** The camera's high-speed modes (size + rate range), from the largest size; empty — none. */
    val highSpeedConfigs: List<HighSpeedConfig>,
    val highSpeedAfMode: Int,
) {
    /** The device rejected all high-speed modes — we do not offer them any more. */
    var highSpeedBroken = false

    val isFront: Boolean get() = lens == LensFacing.Front

    val supportsHighSpeed: Boolean get() = !highSpeedBroken && highSpeedConfigs.isNotEmpty()
}
