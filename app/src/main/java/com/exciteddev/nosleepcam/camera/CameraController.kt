package com.exciteddev.nosleepcam.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import com.exciteddev.nosleepcam.AppLanguage
import com.exciteddev.nosleepcam.Prefs
import com.exciteddev.nosleepcam.R
import com.exciteddev.nosleepcam.StartupTrace
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private const val TAG = "NoSleepCam"

/** Geometry of the preview buffer — needed to rotate and fit the image correctly. */
internal data class PreviewGeometry(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val sensorOrientation: Int,
    val isFront: Boolean,
    val canFocus: Boolean,
    /** Range of exposure compensation indices and the step in EV; evMin == evMax — brightness adjustment is unavailable. */
    val evMin: Int = 0,
    val evMax: Int = 0,
    val evStep: Float = 0f,
    /** The session has a JPEG stream for photos (absent in high-speed mode and when the device rejected such a stream). */
    val canCapture: Boolean = false,
) {
    val isValid: Boolean get() = bufferWidth > 0 && bufferHeight > 0

    val canAdjustExposure: Boolean get() = evMax > evMin

    /** A touch on the frame makes sense: either focus, or at least exposure metering and brightness. */
    val canTap: Boolean get() = canFocus || canAdjustExposure
}

/** [Metered] — a camera without autofocus: only the exposure was metered at this point. */
internal enum class FocusStatus { Searching, Focused, Failed, Metered }

/**
 * Subscriber to camera events. All methods are called on the camera's background thread —
 * the consumer switches to the UI thread itself.
 */
internal interface CameraListener {
    fun onCapabilities(capabilities: CameraCapabilities) {}

    fun onResolutionApplied(resolution: CameraResolution) {}

    fun onGeometry(geometry: PreviewGeometry) {}

    /** Status text for the user; null — all is well, the message is hidden. */
    fun onStatus(status: String?) {}

    fun onFocus(status: FocusStatus) {}

    /** Actual frame rate (fps, integer); 0 — the camera is not running or is being reconfigured. */
    fun onFrameRate(fps: Int) {}

    /** The photo has been written to the gallery. Not called on the camera thread. */
    fun onPhotoSaved(uri: Uri) {}

    /** The photo could not be taken or saved. */
    fun onPhotoFailed() {}
}

/**
 * Controls the camera through Camera2: opening, the preview session, zoom, autofocus
 * (continuous + tap) and automatic reconnection.
 *
 * All camera state changes happen on one background thread ([handler]).
 * External methods only remember the "desired state" and call [reconcile],
 * which brings the actual camera state to the desired one — so the order of calls does not matter.
 */
internal class CameraController private constructor(
    private val context: Context,
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val specReader = CameraSpecReader(manager)
    private val thread = HandlerThread("NoSleepCam.Camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val retryRunnable = Runnable { reconcile() }
    private val captureCallback = CaptureCb()
    private val listeners = CopyOnWriteArrayList<CameraListener>()

    // Caches and blacklists — camera thread only.
    private val specs = HashMap<String, CameraSpec>()
    private val blocked = HashSet<Pair<String, CameraResolution>>()
    private val blockedHighSpeed = HashSet<Pair<String, CameraResolution>>()

    // ---- Desired state (written from any thread) ----
    @Volatile private var wantActive = false

    @Volatile private var wantCameraId: String? = null

    @Volatile private var wantResolution: CameraResolution? = null

    @Volatile private var wantZoom = 1f

    @Volatile private var wantFlash = false

    @Volatile private var wantHighSpeed = false

    @Volatile private var wantEv = 0 // exposure compensation index (brightness); reset by a new touch
    private val zoomPosted = AtomicBoolean(false)

    // ---- Actual state (camera thread only) ----
    private var texture: SurfaceTexture? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var spec: CameraSpec? = null
    private var activeResolution: CameraResolution? = null
    private var generation = 0
    private var opening = false
    private var sessionPending = false
    private var retryDelayMs = 1000L
    private var activeHighSpeed = false // the current session is high-speed
    private var publishedHighSpeed = false // the mode with which the capabilities were last passed to the UI

    // ---- Tap to focus (camera thread only) ----
    private enum class FocusPhase { None, Triggering, Searching }

    private var focusPhase = FocusPhase.None

    /**
     * While true, all requests go in AF_MODE_AUTO. In CONTINUOUS_PICTURE the Honor 10 driver on a START trigger only
     * "locks" the current lens position after ~4 frames without scanning anything (LENS_FOCUS_DISTANCE does not change), so tap
     * focus does not work. In AUTO the same trigger starts a real ACTIVE_SCAN over the chosen region.
     */
    private var focusAutoMode = false
    private var focusStartFrame = 0L
    private var focusToken = 0
    private var afRegion: SensorRect? = null
    private var aeRegion: SensorRect? = null

    private var lastCapabilities: CameraCapabilities? = null

    // ---- Photos: a JPEG stream in the same session as the preview (camera thread only, except savePool) ----
    private var reader: ImageReader? = null // stream of the active session
    private var pendingReader: ImageReader? = null // stream of the session being configured
    private val blockedStill = HashSet<Pair<String, CameraResolution>>()
    private val savePool = Executors.newSingleThreadExecutor()
    private val stillCallback = StillCb()
    private var pendingCaptureRotation = -1 // a photo is waiting for focus to finish (−1 — not waiting)
    private val pendingCaptureRunnable = Runnable { flushPendingCapture() }
    private var capabilitiesPending = false // the camera is open, but the capabilities have not yet been passed to the UI
    private val publishPendingRunnable = Runnable { publishPendingCapabilities() }

    @Volatile private var uiAttached = false

    // =====================================================================
    // Public API (thread-safe)
    // =====================================================================

    /**
     * Subscribes the UI and immediately sends it everything that happened before the subscription
     * (the camera may have opened during warm-up, when there were no subscribers yet).
     */
    fun addListener(listener: CameraListener) =
        post {
            listeners.add(listener)
            uiAttached = true

            lastCapabilities?.let { listener.onCapabilities(it) }

            val resolution = activeResolution
            if (resolution != null) {
                currentGeometry()?.let { listener.onGeometry(it) }
                if (!activeHighSpeed) listener.onResolutionApplied(resolution)
            }

            if (reportedFps > 0) listener.onFrameRate(reportedFps)
        }

    /** A string in the language chosen right now (the application context itself keeps the language of process start). */
    private fun str(
        id: Int,
        vararg args: Any,
    ): String = AppLanguage.wrap(context).getString(id, *args)

    /** The app language changed: names built from string resources are stale, so the caches are dropped. */
    fun onLanguageChanged() =
        post {
            specs.clear()
            lastCapabilities = null
        }

    fun removeListener(listener: CameraListener) {
        listeners.remove(listener)
    }

    fun setActive(active: Boolean) {
        if (wantActive == active) return
        wantActive = active
        post(::reconcile)
    }

    fun setCameraId(id: String?) {
        if (wantCameraId == id) return
        wantCameraId = id
        post(::reconcile)
    }

    fun setResolution(resolution: CameraResolution?) {
        if (wantResolution == resolution) return
        wantResolution = resolution
        post(::reconcile)
    }

    fun setZoom(zoom: Double) {
        wantZoom = zoom.toFloat()
        StartupTrace.req { "setZoom(${"%.2f".format(zoom)}) posted=${zoomPosted.get()}" }

        // The slider generates dozens of events per second — we merge them into a single request update.
        if (zoomPosted.compareAndSet(false, true)) {
            post {
                zoomPosted.set(false)
                applyRepeating()
            }
        }
    }

    /** Flash in torch mode: lit continuously while the camera is running. */
    fun setFlash(on: Boolean) {
        if (wantFlash == on) return
        wantFlash = on
        post(::applyRepeating)
    }

    /** Brightness: exposure compensation index (range and step are in [PreviewGeometry]); clamped to the camera's range. */
    fun setExposureCompensation(index: Int) {
        if (wantEv == index) return
        wantEv = index
        post(::applyRepeating)
    }

    /**
     * High-speed mode (up to 120 fps). Enabled only if the camera declares it; otherwise ignored.
     * The session is rebuilt and the zoom is limited to the declared maximum.
     */
    fun setHighSpeed(on: Boolean) {
        if (wantHighSpeed == on) return
        wantHighSpeed = on
        post(::reconcile)
    }

    fun onSurfaceAvailable(texture: SurfaceTexture) =
        post {
            StartupTrace.mark { "TextureView surface ready" }
            this.texture = texture
            reconcile()
        }

    fun onSurfaceDestroyed(texture: SurfaceTexture) =
        post {
            if (this.texture === texture) {
                closeCamera()
                this.texture = null
            }

            // The controller releases the texture itself, because we asked the TextureView not to release it:
            // this guarantees that the camera is closed BEFORE the surface is destroyed.
            try {
                texture.release()
            } catch (ex: Exception) {
                Log.w(TAG, "Texture release: ${ex.message}")
            }
        }

    /** Focus at point (s, t) ∈ [0,1]² of the "natural" (as on a phone in portrait) image. */
    fun focusAt(
        s: Float,
        t: Float,
    ) = post { startFocus(s, t) }

    fun resetFocus() =
        post {
            afRegion = null
            aeRegion = null
            endFocusLock()
        }

    private fun post(action: () -> Unit) {
        handler.post(action)
    }

    // =====================================================================
    // Reconciling the desired state with the actual one
    // =====================================================================

    private fun reconcile() {
        if (!wantActive) {
            closeCamera()
            return
        }

        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            closeCamera()
            raiseStatus(str(R.string.status_no_permission))
            return
        }

        val targetId = resolveTargetId(wantCameraId)
        if (targetId == null) {
            raiseStatus(str(R.string.status_no_cameras))
            return
        }

        if (device != null && device?.id != targetId) closeCamera()

        // We open the camera device right away, without waiting for the surface: it takes the longest,
        // and it must run in parallel with the UI creation.
        if (device == null) {
            if (!opening) openCamera(targetId)
            return // continue in onCameraOpened
        }

        if (texture == null) return // the camera is open; we will create the session as soon as the surface appears (onSurfaceAvailable)

        if (sessionPending) return // continue in onSessionConfigured / onSessionConfigureFailed

        // High-speed mode: the frame size is dictated by the camera; the resolution chosen by the user is not touched.
        val currentSpec = spec
        val highSpeed = if (currentSpec != null && highSpeedWanted(currentSpec)) resolveHighSpeed(currentSpec) else null

        val resolution = highSpeed?.size ?: resolveResolution()
        if (resolution == null) {
            // All sizes were rejected by the device — clear the blacklist and try again later.
            blocked.clear()
            raiseStatus(str(R.string.status_start_failed))
            scheduleRetry()
            return
        }

        if (session == null || activeResolution != resolution || activeHighSpeed != (highSpeed != null)) {
            createSession(resolution, highSpeed)
            return
        }

        applyRepeating()
    }

    private fun highSpeedWanted(spec: CameraSpec) = wantHighSpeed && spec.supportsHighSpeed

    /** The first high-speed size the device has not yet rejected (the largest — it is also the best). */
    private fun resolveHighSpeed(spec: CameraSpec): HighSpeedConfig? =
        spec.highSpeedConfigs.firstOrNull { (spec.id to it.size) !in blockedHighSpeed }

    /**
     * Which camera to open. If the camera is already known (saved from the last launch) — we take it right away,
     * without iterating over the characteristics of all the device's cameras (each is a separate system call).
     */
    private fun resolveTargetId(wantCameraId: String?): String? {
        if (wantCameraId != null) {
            try {
                val cached = specs[wantCameraId]
                if (cached != null) {
                    if (cached.resolutions.isNotEmpty()) return wantCameraId
                } else if (wantCameraId in manager.cameraIdList) {
                    // The characteristics have not been read yet: we don't spend time on them before opening — openCamera() reads them
                    // in parallel with the system opening the camera.
                    return wantCameraId
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Camera $wantCameraId unavailable: ${ex.message}")
            }
        }

        val cameras = listCameras()
        return (cameras.firstOrNull { isBack(it.id) } ?: cameras.firstOrNull())?.id
    }

    private fun scheduleRetry() {
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, retryDelayMs)
        retryDelayMs = minOf(retryDelayMs * 2, MAX_RETRY_DELAY_MS)
    }

    private fun handleLost(reason: String) {
        Log.w(TAG, "Camera lost: $reason")
        closeCamera()
        raiseStatus(str(R.string.status_connection_lost))
        scheduleRetry()
    }

    // =====================================================================
    // Opening the camera and the session
    // =====================================================================

    @SuppressLint("MissingPermission") // permission is checked in reconcile()
    private fun openCamera(id: String) {
        opening = true
        val gen = ++generation
        try {
            // First we ask the system to open the camera, and only then read the characteristics: while the camera service
            // is busy opening, this thread does not idle. The onOpened callback will not fire until we return.
            manager.openCamera(id, DeviceCb(gen), handler)
            StartupTrace.mark { "openCamera() called" }
            val loaded = loadSpec(id)
            spec = loaded
            if (loaded.resolutions.isEmpty()) {
                // A camera without usable sizes (e.g. an external one that disappeared): forget it and pick another.
                closeCamera()
                wantCameraId = null
                post(::reconcile)
            }
        } catch (ex: Exception) {
            opening = false
            handleLost("openCamera: ${ex.message}")
        }
    }

    private fun onCameraOpened(
        gen: Int,
        camera: CameraDevice,
    ) {
        if (gen != generation) {
            camera.close()
            return
        }

        StartupTrace.mark { "Camera opened (CameraDevice.onOpened)" }
        device = camera
        opening = false
        retryDelayMs = 1000L
        handler.removeCallbacks(retryRunnable)
        Prefs(context).cameraId = camera.id

        // First we start the session (if the surface already exists). We assemble the full list of cameras for the UI only AFTER
        // the session is configured and the first request is sent: session callbacks arrive on this same thread, so assembling it
        // (≈100 ms on the Honor 10) earlier would delay the first frame. The fallback timer is for the case where the session does not start.
        capabilitiesPending = true
        reconcile()
        handler.postDelayed(publishPendingRunnable, CAPABILITIES_FALLBACK_MS)
    }

    private fun onCameraLost(
        gen: Int,
        camera: CameraDevice,
        reason: String,
    ) {
        try {
            camera.close()
        } catch (_: Exception) {
            // already closed
        }

        if (gen != generation) return

        device = null
        handleLost(reason)
    }

    @Suppress("DEPRECATION") // SessionConfiguration — only from API 28, and minSdk = 24
    private fun createSession(resolution: CameraResolution, highSpeed: HighSpeedConfig?) {
        val device = device!!
        val texture = texture!!
        try {
            sessionPending = true
            session = null // the old session closes automatically when a new one is created
            resetFrameRate()

            texture.setDefaultBufferSize(resolution.width, resolution.height)
            val newSurface = Surface(texture)
            surface = newSurface

            val gen = generation
            if (highSpeed != null) {
                // A special session for rates above 30 fps: only the preview surface of the declared size.
                device.createConstrainedHighSpeedCaptureSession(listOf(newSurface), SessionCb(gen, resolution, true, false), handler)
                return
            }

            // Besides the preview, the session has a JPEG stream for photos (the camera delivers a full-size photo from it).
            val still = openStillReader(resolution)
            device.createCaptureSession(
                listOfNotNull(newSurface, still?.surface),
                SessionCb(gen, resolution, false, still != null),
                handler,
            )
        } catch (ex: Exception) {
            sessionPending = false
            handleLost("createCaptureSession: ${ex.message}")
        }
    }

    private fun onSessionConfigured(
        gen: Int,
        session: CameraCaptureSession,
        resolution: CameraResolution,
        highSpeed: Boolean,
    ) {
        if (gen != generation) {
            try {
                session.close()
            } catch (_: Exception) {
                // ignore
            }
            pendingReader?.close()
            pendingReader = null
            return
        }

        StartupTrace.mark { "Session configured (${resolution.key}${if (highSpeed) ", high-speed" else ""})" }
        // The previous session's stream is no longer needed; the new one (if any) becomes active — before raiseGeometry().
        reader?.close()
        reader = pendingReader
        pendingReader = null
        this.session = session
        sessionPending = false
        activeResolution = resolution
        activeHighSpeed = highSpeed
        focusPhase = FocusPhase.None

        val spec = spec!!
        raiseGeometry()

        // In high-speed mode the frame size is not chosen by the user: the UI must not
        // "remember" it as selected (otherwise it would overwrite the real choice after returning to the normal mode).
        if (!highSpeed) emit { it.onResolutionApplied(resolution) }

        raiseStatus(null)

        applyRepeating()

        // In AUTO mode the camera does not focus on its own — we start autofocus manually once.
        if (spec.afMode == CameraMetadata.CONTROL_AF_MODE_AUTO && !highSpeed) {
            triggerAf(CameraMetadata.CONTROL_AF_TRIGGER_START)
        }

        // The mode changed — the zoom maximum and mode support may differ; and after the camera is opened
        // we publish the deferred capabilities right here (the request has already been sent, so this does not delay the first frame).
        if (highSpeed != publishedHighSpeed) publishCapabilities() else publishPendingCapabilities()

        reconcile()
    }

    /** Passes the UI the current capabilities of the active camera. */
    private fun publishCapabilities() {
        capabilitiesPending = false
        val spec = spec ?: return

        publishedHighSpeed = activeHighSpeed
        val capabilities = buildCapabilities(spec.id)
        lastCapabilities = capabilities
        emit { it.onCapabilities(capabilities) }
        StartupTrace.mark { "Camera capabilities passed to the UI" }
    }

    /** Deferred publication of the capabilities after the camera is opened (see onCameraOpened). */
    private fun publishPendingCapabilities() {
        handler.removeCallbacks(publishPendingRunnable)
        if (capabilitiesPending && device != null) publishCapabilities()
    }

    private fun currentGeometry(): PreviewGeometry? {
        val spec = spec ?: return null
        val resolution = activeResolution ?: return null

        // Tap focus in a high-speed session is impossible (it does not accept single requests and triggers).
        // The same goes for brightness: the high-speed request is deliberately minimal, with no exposure compensation.
        return PreviewGeometry(
            resolution.width,
            resolution.height,
            spec.sensorOrientation,
            spec.isFront,
            spec.afMode != CameraMetadata.CONTROL_AF_MODE_OFF && !activeHighSpeed,
            evMin = if (activeHighSpeed) 0 else spec.evMin,
            evMax = if (activeHighSpeed) 0 else spec.evMax,
            evStep = spec.evStep,
            canCapture = !activeHighSpeed && reader != null,
        )
    }

    private fun raiseGeometry() {
        val geometry = currentGeometry() ?: return
        emit { it.onGeometry(geometry) }
    }

    private fun onSessionConfigureFailed(
        gen: Int,
        resolution: CameraResolution,
        highSpeed: Boolean,
        withStill: Boolean,
    ) {
        pendingReader?.close()
        pendingReader = null
        if (gen != generation) return

        sessionPending = false
        publishPendingCapabilities()

        if (withStill) {
            // Perhaps it was precisely the combination of the preview with the JPEG stream that was rejected. We don't discard the resolution because of that:
            // we try the same one without photos (then photos are unavailable for it).
            blockedStill.add(spec!!.id to resolution)
            Log.w(TAG, "Configure with JPEG failed for ${resolution.key}, retrying without")
            reconcile()
            return
        }

        if (highSpeed) {
            // We don't touch the regular size list: it was the high-speed mode of this size that was rejected.
            val failedSpec = spec!!
            blockedHighSpeed.add(failedSpec.id to resolution)
            Log.w(TAG, "High-speed configure failed for ${resolution.key}")

            if (resolveHighSpeed(failedSpec) == null) {
                failedSpec.highSpeedBroken = true
                raiseStatus(str(R.string.status_highspeed_unsupported))
            }

            activeHighSpeed = false
            publishCapabilities()
            reconcile()
            return
        }

        blocked.add(spec!!.id to resolution)
        Log.w(TAG, "Configure failed for ${resolution.key}")

        val next = resolveResolution()
        if (next != null) {
            raiseStatus(
                str(
                    R.string.status_resolution_unsupported,
                    resolution.width,
                    resolution.height,
                    next.width,
                    next.height,
                ),
            )
        }

        reconcile()
    }

    private fun closeCamera() {
        generation++
        opening = false
        sessionPending = false
        focusToken++
        focusPhase = FocusPhase.None
        focusAutoMode = false
        pendingCaptureRotation = -1
        handler.removeCallbacks(pendingCaptureRunnable)
        handler.removeCallbacks(retryRunnable)
        resetFrameRate()

        try {
            session?.close()
        } catch (ex: Exception) {
            Log.w(TAG, "Session close: ${ex.message}")
        }
        session = null

        try {
            device?.close()
        } catch (ex: Exception) {
            Log.w(TAG, "Device close: ${ex.message}")
        }
        device = null

        try {
            surface?.release()
        } catch (ex: Exception) {
            Log.w(TAG, "Surface release: ${ex.message}")
        }
        surface = null

        reader?.close()
        reader = null
        pendingReader?.close()
        pendingReader = null

        activeResolution = null
        activeHighSpeed = false
    }

    // =====================================================================
    // Camera requests: zoom, autofocus mode, regions
    // =====================================================================

    private fun buildRequest(afTrigger: Int): CaptureRequest.Builder {
        val spec = spec!!
        val builder = device!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(surface!!)

        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        if (spec.evMax > spec.evMin) {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, wantEv.coerceIn(spec.evMin, spec.evMax))
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, if (focusAutoMode) CameraMetadata.CONTROL_AF_MODE_AUTO else spec.afMode)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger)

        // Torch keeps the flash on in every frame (including during focusing).
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (wantFlash && spec.hasFlash) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
        )

        // Without an explicit range the Honor 10 driver by default keeps the preview at ~25 fps (range [14,25]), although for
        // most sizes it declares 30; with an explicit [14,30] it gives 30 in good light. The upper bound is the one declared for this size.
        aeFpsRangeFor(spec, activeResolution)?.let { (lower, upper) ->
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(lower, upper))
        }

        val crop = cropForZoom(spec)
        builder.set(CaptureRequest.SCALER_CROP_REGION, crop.toRect())
        applyTapRegions(builder, spec, crop)

        return builder
    }

    private fun applyRepeating() {
        val session = session
        StartupTrace.req {
            "applyRepeating: session=${session != null} device=${device != null} surface=${surface != null} " +
                "spec=${spec != null} active=${activeResolution?.key} hs=$activeHighSpeed wantZoom=${"%.2f".format(wantZoom)}"
        }
        if (session == null || device == null || surface == null || spec == null || activeResolution == null) return

        if (activeHighSpeed) {
            applyHighSpeedRepeating(session)
            return
        }

        try {
            session.setRepeatingRequest(buildRequest(CameraMetadata.CONTROL_AF_TRIGGER_IDLE).build(), captureCallback, handler)
        } catch (ex: Exception) {
            Log.w(TAG, "setRepeatingRequest: ${ex.message}")
        }
    }

    /**
     * A request in a high-speed session. Deliberately minimal: only auto modes, the frame rate range from the camera's list, and zoom.
     * We don't add flash, focus regions and triggers here — such a session does not accept everything, and the Honor driver
     * has already hung on invalid parameters.
     */
    private fun applyHighSpeedRepeating(session: CameraCaptureSession) {
        val spec = spec!!
        val config = spec.highSpeedConfigs.firstOrNull { it.size == activeResolution }
        if (config == null) {
            StartupTrace.req { "HS burst: no config for ${activeResolution?.key}" }
            return
        }

        try {
            val highSpeedSession = session as CameraConstrainedHighSpeedCaptureSession
            val builder = device!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface!!)

            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AF_MODE, spec.highSpeedAfMode)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(config.lower, config.upper))
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropForZoom(spec).toRect())

            val requests = highSpeedSession.createHighSpeedRequestList(builder.build())
            StartupTrace.req { "HS burst: crop=${cropForZoom(spec)} requests=${requests.size}" }
            highSpeedSession.setRepeatingBurst(requests, captureCallback, handler)
            StartupTrace.req { "HS burst: setRepeatingBurst returned" }
        } catch (ex: Exception) {
            Log.w(TAG, "High-speed burst: ${ex.message}")
            StartupTrace.req { "HS burst EXCEPTION: ${ex.javaClass.simpleName}: ${ex.message}" }
        }
    }

    /** Maximum zoom offered at the moment: in high-speed mode — only the one declared by the camera. */
    private fun effectiveMaxZoom(spec: CameraSpec) = if (highSpeedWanted(spec)) spec.declaredMaxZoom else spec.maxZoom

    private fun currentZoom(spec: CameraSpec) = wantZoom.coerceIn(1f, maxOf(1f, effectiveMaxZoom(spec)))

    /** The sensor area that must be shown at the current zoom (centred). */
    private fun cropForZoom(spec: CameraSpec) = SensorGeometry.cropForZoom(spec.activeArray, currentZoom(spec))

    /**
     * Tap focus/metering regions go into a request only when they are inside the visible area:
     * a region outside it is ignored by the camera.
     */
    private fun applyTapRegions(
        builder: CaptureRequest.Builder,
        spec: CameraSpec,
        crop: SensorRect,
    ) {
        afRegion
            ?.takeIf { spec.maxAfRegions > 0 && crop.containsCenterOf(it) }
            ?.let { builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it.toMetering())) }
        aeRegion
            ?.takeIf { spec.maxAeRegions > 0 && crop.containsCenterOf(it) }
            ?.let { builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it.toMetering())) }
    }

    private fun SensorRect.toMetering() = MeteringRectangle(left, top, width, height, MeteringRectangle.METERING_WEIGHT_MAX)

    // =====================================================================
    // Photo
    // =====================================================================

    /** Creates a JPEG receiver for the preview resolution; null — photos are unavailable for it. */
    private fun openStillReader(resolution: CameraResolution): ImageReader? {
        val spec = spec ?: return null
        if ((spec.id to resolution) in blockedStill) return null

        val size = PhotoMath.pickJpegSize(spec.jpegSizes, resolution) ?: return null
        val newReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        newReader.setOnImageAvailableListener(::onStillAvailable, handler)
        pendingReader?.close()
        pendingReader = newReader
        StartupTrace.req { "JPEG ${size.key} for preview ${resolution.key}" }
        return newReader
    }

    /**
     * Takes a photo: a full-size JPEG with the same zoom, brightness, flash and focus as in the preview.
     * [displayRotation] is the current screen rotation (0/90/180/270), which determines how the photo is rotated.
     * The result is [CameraListener.onPhotoSaved] or [CameraListener.onPhotoFailed].
     */
    fun capturePhoto(displayRotation: Int) =
        post {
            if (focusPhase != FocusPhase.None) {
                // Tap focus (the first tap of a double tap) is still in progress: we wait for the result so that the photo does not come out unfocused.
                pendingCaptureRotation = displayRotation
                handler.removeCallbacks(pendingCaptureRunnable)
                handler.postDelayed(pendingCaptureRunnable, CAPTURE_WAIT_FOR_FOCUS_MS)
            } else {
                doCapturePhoto(displayRotation)
            }
        }

    /** Focus finished (or the wait timed out) — we take the photo that was waiting for it. */
    private fun flushPendingCapture() {
        handler.removeCallbacks(pendingCaptureRunnable)
        val rotation = pendingCaptureRotation
        if (rotation < 0) return

        pendingCaptureRotation = -1
        doCapturePhoto(rotation)
    }

    private fun doCapturePhoto(displayRotation: Int) {
        val spec = spec
        val session = session
        val reader = reader
        StartupTrace.req {
            "capturePhoto: session=${session != null} reader=${reader != null} hs=$activeHighSpeed rotation=$displayRotation"
        }
        if (spec == null || session == null || reader == null || device == null || activeHighSpeed) {
            emit { it.onPhotoFailed() }
            return
        }

        try {
            val builder = device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            // After tap focus the lens is locked in AUTO mode — we keep it for the photo as well.
            builder.set(CaptureRequest.CONTROL_AF_MODE, if (focusAutoMode) CameraMetadata.CONTROL_AF_MODE_AUTO else spec.afMode)
            if (spec.evMax > spec.evMin) {
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, wantEv.coerceIn(spec.evMin, spec.evMax))
            }
            // Flash in torch mode stays on for the photo as well.
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (wantFlash && spec.hasFlash) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
            )
            val crop = cropForZoom(spec)
            builder.set(CaptureRequest.SCALER_CROP_REGION, crop.toRect())
            applyTapRegions(builder, spec, crop)

            builder.set(CaptureRequest.JPEG_ORIENTATION, PhotoMath.jpegOrientation(spec.sensorOrientation, displayRotation, spec.isFront))
            builder.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)

            session.capture(builder.build(), stillCallback, handler)
        } catch (ex: Exception) {
            Log.w(TAG, "capturePhoto: ${ex.message}")
            emit { it.onPhotoFailed() }
        }
    }

    /** The JPEG is ready: we take the bytes and hand the gallery write over to a separate thread so as not to block the camera. */
    private fun onStillAvailable(source: ImageReader) {
        val image =
            try {
                source.acquireNextImage()
            } catch (ex: Exception) {
                Log.w(TAG, "acquireNextImage: ${ex.message}")
                null
            } ?: return

        val bytes =
            try {
                val buffer = image.planes[0].buffer
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } finally {
                image.close()
            }

        val takenAt = System.currentTimeMillis()
        StartupTrace.req { "JPEG received: ${bytes.size} bytes" }
        savePool.execute {
            val uri = PhotoSaver.save(context, bytes, takenAt)
            if (uri != null) emit { it.onPhotoSaved(uri) } else emit { it.onPhotoFailed() }
        }
    }

    // =====================================================================
    // Tap focus
    // =====================================================================

    private fun startFocus(
        s: Float,
        t: Float,
    ) {
        val spec = spec
        val resolution = activeResolution
        val session = session
        val noAf = spec != null && spec.afMode == CameraMetadata.CONTROL_AF_MODE_OFF
        if (session == null || spec == null || resolution == null || activeHighSpeed ||
            (noAf && spec.maxAeRegions == 0)
        ) {
            emit { it.onFocus(FocusStatus.Failed) } // remove the indicator that is already shown
            return
        }

        val visible = SensorGeometry.visibleRect(cropForZoom(spec), resolution.width, resolution.height)
        val region = SensorGeometry.focusRegion(s, t, spec.sensorOrientation, visible, spec.activeArray)
        afRegion = if (spec.maxAfRegions > 0 && !noAf) region else null
        aeRegion = if (spec.maxAeRegions > 0) region else null

        if (noAf) {
            // A camera without autofocus (usually the front one): at this point we only meter the exposure.
            applyRepeating()
            emit { it.onFocus(FocusStatus.Metered) }
            return
        }

        val token = ++focusToken
        focusPhase = FocusPhase.Triggering
        focusAutoMode = spec.hasAfAuto
        emit { it.onFocus(FocusStatus.Searching) }
        StartupTrace.req {
            "TAP s=${"%.2f".format(s)} t=${"%.2f".format(t)} -> sensor region=$region " +
                "visible=$visible afMode=${spec.afMode} afRegionSet=${afRegion != null}"
        }

        try {
            // Reset the previous trigger, then start a new one and update the regions in the repeating request.
            session.capture(buildRequest(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL).build(), captureCallback, handler)
            session.capture(buildRequest(CameraMetadata.CONTROL_AF_TRIGGER_START).build(), captureCallback, handler)
            session.setRepeatingRequest(buildRequest(CameraMetadata.CONTROL_AF_TRIGGER_IDLE).build(), captureCallback, handler)
        } catch (ex: Exception) {
            Log.w(TAG, "startFocus: ${ex.message}")
            focusPhase = FocusPhase.None
            return
        }

        handler.postDelayed({
            if (token == focusToken && focusPhase != FocusPhase.None) {
                emit { it.onFocus(FocusStatus.Failed) }
                endFocusLock()
            }
        }, FOCUS_TIMEOUT_MS)
    }

    private fun onFocusResult(success: Boolean) {
        val token = focusToken
        focusPhase = FocusPhase.None
        emit { it.onFocus(if (success) FocusStatus.Focused else FocusStatus.Failed) }
        flushPendingCapture() // the lens is locked (AUTO mode) — we take the photo that was waiting for focus right now

        // Briefly hold the lock, then return to continuous autofocus (the region stays).
        handler.postDelayed({
            if (token == focusToken) endFocusLock()
        }, FOCUS_HOLD_MS)
    }

    private fun endFocusLock() {
        focusToken++
        focusPhase = FocusPhase.None
        flushPendingCapture()
        focusAutoMode = false
        triggerAf(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        applyRepeating()
    }

    private fun triggerAf(trigger: Int) {
        val session = session
        if (session == null || device == null || surface == null || spec == null || activeHighSpeed) {
            return // a high-speed session does not accept single requests and triggers
        }

        try {
            session.capture(buildRequest(trigger).build(), captureCallback, handler)
        } catch (ex: Exception) {
            Log.w(TAG, "triggerAf($trigger): ${ex.message}")
        }
    }

    private fun onCaptureCompleted(
        request: CaptureRequest,
        result: TotalCaptureResult,
    ) {
        measureFrameRate(result)
        if (!activeHighSpeed) {
            checkZoomHonored(request, result) // in high-speed mode the crop region may not apply — that is not zoom "clamping"
        }

        traceAf(request, result)
        if (focusPhase == FocusPhase.None) return

        if (focusPhase == FocusPhase.Triggering) {
            // We wait for the frame in which the START trigger was actually sent.
            val trigger = request.get(CaptureRequest.CONTROL_AF_TRIGGER) ?: -1
            if (trigger != CameraMetadata.CONTROL_AF_TRIGGER_START) return

            focusStartFrame = result.frameNumber
            focusPhase = FocusPhase.Searching
        }

        if (result.frameNumber < focusStartFrame) return

        when (result.get(CaptureResult.CONTROL_AF_STATE)) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> onFocusResult(true)
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> onFocusResult(false)
        }
    }

    private var traceAfLastState = -1

    /** AF diagnostics (tag NSC-Req): state, mode, request trigger, regions in the response, lens distance. */
    private fun traceAf(
        request: CaptureRequest,
        result: TotalCaptureResult,
    ) = StartupTrace.req {
        val state = result.get(CaptureResult.CONTROL_AF_STATE) ?: -1
        val changed = state != traceAfLastState
        traceAfLastState = state
        if (!changed && focusPhase == FocusPhase.None) return@req ""
        val regions = result.get(CaptureResult.CONTROL_AF_REGIONS)?.firstOrNull()?.rect
        "AF frame=${result.frameNumber} state=$state mode=${result.get(CaptureResult.CONTROL_AF_MODE)} " +
            "reqTrigger=${request.get(CaptureRequest.CONTROL_AF_TRIGGER)} phase=$focusPhase " +
            "focusDist=${result.get(CaptureResult.LENS_FOCUS_DISTANCE)} regionInResult=$regions"
    }

    // ---------------------------------------------------------------------
    // Actual frame rate (shown next to the flash icon)
    // ---------------------------------------------------------------------

    private val frameRateMeter = FrameRateMeter()
    private var reportedFps = 0

    /**
     * Counts frames by sensor timestamps (more accurate than by callback arrival time) in a ≈1 s window
     * and notifies the UI when the integer value changes.
     */
    private fun measureFrameRate(result: TotalCaptureResult) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val fps = frameRateMeter.onFrame(timestamp) ?: return
        traceFps(fps, result)
        publishFrameRate(fps.roundToInt())
    }

    private fun publishFrameRate(fps: Int) {
        if (fps == reportedFps) return

        reportedFps = fps
        emit { it.onFrameRate(fps) }
    }

    /** The camera was closed or reconfigured — the old value is no longer reliable. */
    private fun resetFrameRate() {
        frameRateMeter.reset()
        publishFrameRate(0)
    }

    private fun traceFps(
        fps: Double,
        result: TotalCaptureResult,
    ) = StartupTrace.fps {
        val key = activeResolution?.key ?: "?"
        val declared = spec?.frameRates?.get(activeResolution) ?: 0.0
        val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val frameNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
        val aeRange = spec?.let { aeFpsRangeFor(it, activeResolution) }
        "$key: measured ${"%.1f".format(fps)} fps, declared ${"%.0f".format(declared)}, " +
            "AE range ${aeRange?.let { "[${it.first},${it.second}]" } ?: "not set"}; " +
            "exposure ${"%.1f".format(exposureNs / 1e6)} ms, frame duration ${"%.1f".format(frameNs / 1e6)} ms"
    }

    // ---------------------------------------------------------------------
    // Zoom beyond the declared maximum (Huawei/Honor only, see HUAWEI_MAX_ZOOM)
    //
    // If the camera silently clamps the requested zoom (the frame result shows a wider area than requested), we lower
    // the maximum to what is actually achievable, so that the slider does not promise more than there is. A driver hang cannot be fixed this way:
    // in that case the camera thread blocks inside a driver call, so we do not go beyond the verified limits at all.
    // ---------------------------------------------------------------------

    private fun checkZoomHonored(
        request: CaptureRequest,
        result: TotalCaptureResult,
    ) {
        val spec = spec ?: return
        if (spec.maxZoom <= spec.declaredMaxZoom + 0.01f) return // we request within the declared range — nothing to check

        val asked = request.get(CaptureRequest.SCALER_CROP_REGION)
        val applied = result.get(CaptureResult.SCALER_CROP_REGION)
        if (asked == null || applied == null || !SensorGeometry.isCropClamped(asked.width(), applied.width())) return

        val limit = SensorGeometry.achievedZoom(spec.activeArray.width, applied.width(), spec.declaredMaxZoom)
        if (limit >= spec.maxZoom - 0.05f) return

        Log.w(TAG, "Camera ${spec.id} crops the requested zoom; max ${spec.maxZoom}x -> ${limit}x")
        spec.maxZoom = limit
        if (wantZoom > limit) wantZoom = limit

        // The UI lowers the slider to the new maximum.
        val capabilities = buildCapabilities(spec.id)
        lastCapabilities = capabilities
        emit { it.onCapabilities(capabilities) }
        applyRepeating()
    }

    // =====================================================================
    // Camera characteristics
    // =====================================================================

    private fun isBack(id: String) = !loadSpec(id).isFront

    private fun loadSpec(id: String): CameraSpec = specs[id] ?: specReader.read(id).also { specs[id] = it }

    /** The user-facing name of a camera ("Back camera"), in the language chosen right now. */
    private fun nameOf(spec: CameraSpec) =
        str(
            when (spec.lens) {
                LensFacing.Front -> R.string.camera_front
                LensFacing.External -> R.string.camera_external
                LensFacing.Back -> R.string.camera_back
            },
        )

    /** Cameras suitable for showing an image (without depth/IR sensors etc.). */
    private fun listCameras(): List<CameraDescriptor> {
        val usable = ArrayList<CameraSpec>()
        val ids =
            try {
                manager.cameraIdList
            } catch (ex: Exception) {
                Log.w(TAG, "Camera id list: ${ex.message}")
                emptyArray()
            }

        for (id in ids) {
            try {
                val spec = loadSpec(id)
                if (spec.backwardCompatible && spec.resolutions.isNotEmpty()) usable.add(spec)
            } catch (ex: Exception) {
                Log.w(TAG, "Camera $id skipped: ${ex.message}")
            }
        }

        // If there are several identical cameras, we number them: "Back camera 1", "Back camera 2".
        return usable
            .groupBy { nameOf(it) }
            .values
            .flatMap { group ->
                if (group.size == 1) {
                    group.map { CameraDescriptor(it.id, nameOf(it)) }
                } else {
                    group.mapIndexed { i, s -> CameraDescriptor(s.id, str(R.string.camera_numbered, nameOf(s), i + 1)) }
                }
            }.sortedWith(compareBy({ if (isBack(it.id)) 0 else 1 }, { it.id }))
    }

    private fun buildCapabilities(id: String): CameraCapabilities {
        val spec = loadSpec(id)
        val cameras = listCameras()
        val highSpeed = if (spec.supportsHighSpeed) resolveHighSpeed(spec) else null
        return CameraCapabilities(
            camera = cameras.firstOrNull { it.id == id } ?: CameraDescriptor(id, nameOf(spec)),
            availableCameras = cameras,
            resolutions = spec.resolutions,
            frameRates = spec.frameRates,
            highSpeedFps = highSpeed?.upper ?: 0,
            highSpeedResolution = highSpeed?.size,
            maxZoom = effectiveMaxZoom(spec).toDouble(),
            supportsAutoFocus = spec.afMode != CameraMetadata.CONTROL_AF_MODE_OFF,
            supportsFlash = spec.hasFlash,
            supportsTapToFocus = spec.afMode != CameraMetadata.CONTROL_AF_MODE_OFF && spec.maxAfRegions > 0,
        )
    }

    /**
     * Picks a resolution: the requested one (if the camera supports it and it has not been rejected),
     * otherwise a balanced one — the largest up to ~2 MP with the sensor's full field of view.
     */
    private fun resolveResolution(): CameraResolution? {
        val spec = spec ?: return null

        val candidates = spec.resolutions.filter { (spec.id to it) !in blocked }
        val sensorAspect = spec.activeArray.width.toDouble() / spec.activeArray.height
        return CameraSpecRules.pickResolution(candidates, wantResolution, sensorAspect)
    }

    /** Target frame rate range for the chosen resolution (see [CameraSpecRules.aeFpsRange]). */
    private fun aeFpsRangeFor(
        spec: CameraSpec,
        resolution: CameraResolution?,
    ) = resolution?.let { CameraSpecRules.aeFpsRange(spec.aeFpsRanges, spec.frameRates[it]) }

    private fun raiseStatus(status: String?) = emit { it.onStatus(status) }

    private inline fun emit(block: (CameraListener) -> Unit) {
        for (listener in listeners) block(listener)
    }

    // =====================================================================
    // Camera2 callbacks
    // =====================================================================

    private inner class DeviceCb(
        private val generation: Int,
    ) : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) = onCameraOpened(generation, camera)

        override fun onDisconnected(camera: CameraDevice) = onCameraLost(generation, camera, "camera disconnected")

        override fun onError(
            camera: CameraDevice,
            error: Int,
        ) = onCameraLost(generation, camera, "camera error $error")
    }

    private inner class SessionCb(
        private val generation: Int,
        private val resolution: CameraResolution,
        private val highSpeed: Boolean,
        private val withStill: Boolean,
    ) : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) = onSessionConfigured(generation, session, resolution, highSpeed)

        override fun onConfigureFailed(session: CameraCaptureSession) =
            onSessionConfigureFailed(generation, resolution, highSpeed, withStill)
    }

    /** Result of the photo: success arrives via ImageReader, and here only the error. */
    private inner class StillCb : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: android.hardware.camera2.CaptureFailure,
        ) {
            Log.w(TAG, "Still capture failed: reason ${failure.reason}")
            emit { it.onPhotoFailed() }
        }
    }

    private inner class CaptureCb : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) = this@CameraController.onCaptureCompleted(request, result)
    }

    companion object {
        private const val FOCUS_HOLD_MS = 1200L // how long to hold the focus lock after a touch
        private const val FOCUS_TIMEOUT_MS = 4000L // maximum wait for the focus result
        private const val MAX_RETRY_DELAY_MS = 8000L
        private const val CAPABILITIES_FALLBACK_MS = 1500L
        private const val JPEG_QUALITY: Byte = 95
        private const val CAPTURE_WAIT_FOR_FOCUS_MS = 1500L // how long a photo waits for tap focus

        /** If the UI never connected (the process was started not to show the screen) — we do not keep the camera. */
        private const val WARM_UP_TIMEOUT_MS = 15_000L

        // A shared instance for the whole process: the camera can be warmed up even before the UI is created.
        private val sharedLock = Any()

        // The controller holds only the ApplicationContext (see getShared), so there is no activity leak here.
        @SuppressLint("StaticFieldLeak")
        private var shared: CameraController? = null

        /** The only controller of the process. The UI connects to it rather than creating its own. */
        fun getShared(context: Context): CameraController =
            synchronized(sharedLock) {
                shared ?: CameraController(context.applicationContext ?: context).also { shared = it }
            }

        /**
         * Starts opening the camera right at process start — in parallel with the UI creation
         * (hundreds of milliseconds during which the camera used to just wait). Does nothing without the camera permission.
         * All the work (reading settings, camera characteristics) is on the camera thread, not the main one.
         */
        fun warmUp(context: Context) {
            try {
                if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

                val controller = getShared(context)
                controller.post {
                    val prefs = Prefs(context)
                    val cameraId = prefs.cameraId
                    controller.wantCameraId = cameraId
                    // We pick up the saved resolution already here: otherwise the first session would start with the default one
                    // and immediately be rebuilt as soon as the UI reports the chosen one.
                    controller.wantResolution = cameraId?.let(prefs::resolution)
                    controller.wantActive = true
                    controller.reconcile()
                }

                controller.handler.postDelayed({
                    if (!controller.uiAttached) controller.setActive(false)
                }, WARM_UP_TIMEOUT_MS)
            } catch (ex: Exception) {
                Log.w(TAG, "warmUp failed: ${ex.message}")
            }
        }
    }
}
