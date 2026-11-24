package com.exciteddev.nosleepcam.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.exciteddev.nosleepcam.LOG_TAG
import com.exciteddev.nosleepcam.StartupTrace
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controls the camera through Camera2: opening, the preview session, zoom and automatic reconnection.
 *
 * All camera state changes happen on one background thread ([handler]).
 * External methods only remember the "desired state" and call [reconcile],
 * which brings the actual camera state to the desired one — so the order of calls does not matter.
 *
 * The controller owns the device and the session. The rest is split by responsibility, and every part runs on the same
 * thread and gets the running camera as an [ActiveCamera]:
 * - [TapFocus] — tap to focus (decisions in [FocusRules]);
 * - [StillCapture] — photos;
 * - [RequestFactory] — building requests;
 * - [CameraCatalog] — which cameras exist and which one to open;
 * - [FrameRateReporter] — the measured frame rate.
 */
internal class CameraController(
    context: Context,
) {
    private val context: Context = context.applicationContext ?: context
    private val thread = HandlerThread("NoSleepCam.Camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val retryRunnable = Runnable { reconcile() }
    private val events = CameraEvents()
    private val host = Host()
    private val captureCallback = CaptureCb()
    private val manager = this.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val catalog = CameraCatalog(manager)
    private val focus = TapFocus(handler, events, host)
    private val stills = StillCapture(this.context, handler, events, host, focus)
    private val frameRate = FrameRateReporter(events)
    private val zoom = ZoomControl()
    private val highSpeed = HighSpeedPolicy()

    // Sizes the device rejected — camera thread only. The specs stay exactly as the system reported them.
    private val blocked = HashSet<Pair<String, CameraResolution>>()

    // ---- Desired state (written from any thread) ----
    @Volatile private var wantActive = false

    @Volatile private var wantCameraId: String? = null

    @Volatile private var wantResolution: CameraResolution? = null

    @Volatile private var wantFlash = false

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
    private var lastCapabilities: CameraCapabilities? = null
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
            events.add(listener)
            uiAttached = true

            lastCapabilities?.let { listener.onCapabilities(it) }

            val resolution = activeResolution
            if (resolution != null) {
                currentGeometry()?.let { listener.onGeometry(it) }
                if (!activeHighSpeed) listener.onResolutionApplied(resolution)
            }

            if (frameRate.reported > 0) listener.onFrameRate(frameRate.reported)
        }

    fun removeListener(listener: CameraListener) {
        events.remove(listener)
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

    fun setZoom(value: Double) {
        val requested = value.toFloat()
        if (zoom.requested == requested) return

        zoom.requested = requested
        StartupTrace.req { "setZoom(${"%.2f".format(value)}) posted=${zoomPosted.get()}" }

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
        if (highSpeed.wanted == on) return
        highSpeed.wanted = on
        post(::reconcile)
    }

    /** What the camera should start with when it is opened before the UI exists (the values saved at the last launch). */
    class WarmUpSettings(
        val cameraId: String?,
        val resolution: CameraResolution?,
    )

    /**
     * Starts opening the camera right at process start — in parallel with the UI creation
     * (hundreds of milliseconds during which the camera used to just wait). Does nothing without the camera permission.
     * All the work, including [loadSettings] (reading preferences from disk), is on the camera thread, not the main one.
     */
    fun warmUp(loadSettings: () -> WarmUpSettings) {
        try {
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

            post {
                val settings = loadSettings()
                wantCameraId = settings.cameraId
                // We pick up the saved resolution already here: otherwise the first session would start with the default one
                // and immediately be rebuilt as soon as the UI reports the chosen one.
                wantResolution = settings.resolution
                wantActive = true
                reconcile()
            }

            handler.postDelayed({
                if (!uiAttached) setActive(false)
            }, WARM_UP_TIMEOUT_MS)
        } catch (ex: Exception) {
            Log.w(LOG_TAG, "warmUp failed: ${ex.message}")
        }
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
                Log.w(LOG_TAG, "Texture release: ${ex.message}")
            }
        }

    /** Focus at point (s, t) ∈ [0,1]² of the "natural" (as on a phone in portrait) image. */
    fun focusAt(
        s: Float,
        t: Float,
    ) = post { focus.tap(s, t) }

    /**
     * Takes a photo: a full-size JPEG with the same zoom, brightness, flash and focus as in the preview.
     * [displayRotation] is the current screen rotation (0/90/180/270), which determines how the photo is rotated.
     * The result is [CameraListener.onPhotoSaved] or [CameraListener.onPhotoFailed].
     */
    fun capturePhoto(displayRotation: Int) = post { stills.capture(displayRotation) }

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

        val targetId = targetCameraId() ?: return

        if (device != null && device?.id != targetId) closeCamera()

        // We open the camera device right away, without waiting for the surface: it takes the longest,
        // and it must run in parallel with the UI creation.
        if (device == null) {
            if (!opening) openCamera(targetId)
            return // continue in onCameraOpened
        }

        // The camera is open; the session is created as soon as the surface appears (onSurfaceAvailable),
        // or continues in onSessionConfigured / onSessionConfigureFailed when it is already being configured.
        if (texture == null || sessionPending) return

        ensureSession()
    }

    /** The camera to work with, or null when none can be used right now (the reason is reported to the UI). */
    private fun targetCameraId(): String? {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            closeCamera()
            raiseStatus(CameraStatus.NoPermission)
            return null
        }

        val targetId = catalog.resolveTargetId(wantCameraId)
        if (targetId == null) raiseStatus(CameraStatus.NoCameras)
        return targetId
    }

    /** Creates the session if the resolution or mode changed, otherwise only refreshes the repeating request. */
    private fun ensureSession() {
        // High-speed mode: the frame size is dictated by the camera; the resolution chosen by the user is not touched.
        val currentSpec = spec
        val fastConfig = if (currentSpec != null && highSpeed.isActive(currentSpec)) highSpeed.config(currentSpec) else null

        val resolution = fastConfig?.size ?: resolveResolution()
        if (resolution == null) {
            // All sizes were rejected by the device — clear the blacklist and try again later.
            blocked.clear()
            raiseStatus(CameraStatus.StartFailed)
            scheduleRetry()
            return
        }

        if (session == null || activeResolution != resolution || activeHighSpeed != (fastConfig != null)) {
            createSession(resolution, fastConfig)
            return
        }

        applyRepeating()
    }

    private fun scheduleRetry() {
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, retryDelayMs)
        retryDelayMs = minOf(retryDelayMs * 2, MAX_RETRY_DELAY_MS)
    }

    private fun handleLost(reason: String) {
        Log.w(LOG_TAG, "Camera lost: $reason")
        closeCamera()
        raiseStatus(CameraStatus.ConnectionLost)
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
            val loaded = catalog.spec(id)
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
    private fun createSession(
        resolution: CameraResolution,
        fastConfig: HighSpeedConfig?,
    ) {
        val device = device ?: return
        val texture = texture ?: return
        try {
            sessionPending = true
            session = null // the old session closes automatically when a new one is created
            frameRate.reset()

            texture.setDefaultBufferSize(resolution.width, resolution.height)
            val newSurface = Surface(texture)
            surface = newSurface

            val gen = generation
            if (fastConfig != null) {
                // A special session for rates above 30 fps: only the preview surface of the declared size.
                device.createConstrainedHighSpeedCaptureSession(listOf(newSurface), SessionCb(gen, resolution, true, false), handler)
                return
            }

            // Besides the preview, the session has a JPEG stream for photos (the camera delivers a full-size photo from it).
            val still = spec?.let { stills.openStream(it, resolution) }
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
        isHighSpeed: Boolean,
    ) {
        if (gen != generation) {
            try {
                session.close()
            } catch (_: Exception) {
                // ignore
            }
            stills.discardPendingStream()
            return
        }

        StartupTrace.mark { "Session configured (${resolution.key}${if (isHighSpeed) ", high-speed" else ""})" }
        // The previous session's stream is no longer needed; the new one (if any) becomes active — before raiseGeometry().
        stills.activateStream()
        this.session = session
        sessionPending = false
        activeResolution = resolution
        activeHighSpeed = isHighSpeed
        focus.onSessionReplaced()

        val spec = spec ?: return
        raiseGeometry()

        // In high-speed mode the frame size is not chosen by the user: the UI must not
        // "remember" it as selected (otherwise it would overwrite the real choice after returning to the normal mode).
        if (!isHighSpeed) events.emit { it.onResolutionApplied(resolution) }

        raiseStatus(null)

        applyRepeating()

        // In AUTO mode the camera does not focus on its own — we start autofocus manually once.
        if (spec.afMode == CameraMetadata.CONTROL_AF_MODE_AUTO && !isHighSpeed) {
            focus.triggerAf(CameraMetadata.CONTROL_AF_TRIGGER_START)
        }

        // The mode changed — the zoom maximum and mode support may differ; and after the camera is opened
        // we publish the deferred capabilities right here (the request has already been sent, so this does not delay the first frame).
        if (isHighSpeed != publishedHighSpeed) publishCapabilities() else publishPendingCapabilities()

        reconcile()
    }

    /** Passes the UI the current capabilities of the active camera. */
    private fun publishCapabilities() {
        capabilitiesPending = false
        val spec = spec ?: return

        publishedHighSpeed = activeHighSpeed
        val capabilities = buildCapabilities(spec)
        lastCapabilities = capabilities
        events.emit { it.onCapabilities(capabilities) }
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
            canCapture = !activeHighSpeed && stills.hasStream,
        )
    }

    private fun raiseGeometry() {
        val geometry = currentGeometry() ?: return
        events.emit { it.onGeometry(geometry) }
    }

    private fun onSessionConfigureFailed(
        gen: Int,
        resolution: CameraResolution,
        wasHighSpeed: Boolean,
        withStill: Boolean,
    ) {
        stills.discardPendingStream()
        if (gen != generation) return

        sessionPending = false
        publishPendingCapabilities()
        val failedSpec = spec ?: return

        if (withStill) {
            // Perhaps it was precisely the combination of the preview with the JPEG stream that was rejected. We don't discard the resolution because of that:
            // we try the same one without photos (then photos are unavailable for it).
            stills.block(failedSpec, resolution)
            Log.w(LOG_TAG, "Configure with JPEG failed for ${resolution.key}, retrying without")
            reconcile()
            return
        }

        if (wasHighSpeed) {
            // We don't touch the regular size list: it was the high-speed mode of this size that was rejected.
            Log.w(LOG_TAG, "High-speed configure failed for ${resolution.key}")

            if (highSpeed.reject(failedSpec, resolution)) raiseStatus(CameraStatus.HighSpeedUnsupported)

            activeHighSpeed = false
            publishCapabilities()
            reconcile()
            return
        }

        blocked.add(failedSpec.id to resolution)
        Log.w(LOG_TAG, "Configure failed for ${resolution.key}")

        val next = resolveResolution()
        if (next != null) {
            raiseStatus(CameraStatus.ResolutionUnsupported(resolution, next))
        }

        reconcile()
    }

    private fun closeCamera() {
        generation++
        opening = false
        sessionPending = false
        focus.cancel()
        handler.removeCallbacks(retryRunnable)
        frameRate.reset()

        try {
            session?.close()
        } catch (ex: Exception) {
            Log.w(LOG_TAG, "Session close: ${ex.message}")
        }
        session = null

        try {
            device?.close()
        } catch (ex: Exception) {
            Log.w(LOG_TAG, "Device close: ${ex.message}")
        }
        device = null

        try {
            surface?.release()
        } catch (ex: Exception) {
            Log.w(LOG_TAG, "Surface release: ${ex.message}")
        }
        surface = null

        stills.release()

        activeResolution = null
        activeHighSpeed = false
    }

    // =====================================================================
    // Camera requests and zoom
    // =====================================================================

    private fun activeCamera(): ActiveCamera? {
        val device = device ?: return null
        val session = session ?: return null
        val surface = surface ?: return null
        val spec = spec ?: return null
        val resolution = activeResolution ?: return null
        return ActiveCamera(device, session, surface, spec, resolution, activeHighSpeed)
    }

    private fun requestParams(spec: CameraSpec) =
        RequestParams(
            evIndex = wantEv,
            torch = wantFlash,
            crop = cropForZoom(spec),
            afAutoMode = focus.autoMode,
            afRegion = focus.afRegion,
            aeRegion = focus.aeRegion,
        )

    private fun applyRepeating() {
        val camera = activeCamera()
        StartupTrace.req {
            "applyRepeating: session=${session != null} device=${device != null} surface=${surface != null} " +
                "spec=${spec != null} active=${activeResolution?.key} hs=$activeHighSpeed wantZoom=${"%.2f".format(zoom.requested)}"
        }
        if (camera == null) return

        if (camera.highSpeed) {
            applyHighSpeedRepeating(camera)
            return
        }

        try {
            val request = host.previewRequest(camera, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            camera.session.setRepeatingRequest(request, captureCallback, handler)
        } catch (ex: Exception) {
            Log.w(LOG_TAG, "setRepeatingRequest: ${ex.message}")
        }
    }

    private fun applyHighSpeedRepeating(camera: ActiveCamera) {
        val config = camera.spec.highSpeedConfigs.firstOrNull { it.size == camera.resolution }
        if (config == null) {
            StartupTrace.req { "HS burst: no config for ${camera.resolution.key}" }
            return
        }

        try {
            val highSpeedSession = camera.session as CameraConstrainedHighSpeedCaptureSession
            val crop = cropForZoom(camera.spec)
            val requests = highSpeedSession.createHighSpeedRequestList(RequestFactory.highSpeed(camera, config, crop))
            StartupTrace.req { "HS burst: crop=$crop requests=${requests.size}" }
            highSpeedSession.setRepeatingBurst(requests, captureCallback, handler)
            StartupTrace.req { "HS burst: setRepeatingBurst returned" }
        } catch (ex: Exception) {
            Log.w(LOG_TAG, "High-speed burst: ${ex.message}")
            StartupTrace.req { "HS burst EXCEPTION: ${ex.javaClass.simpleName}: ${ex.message}" }
        }
    }

    /** The sensor area that must be shown at the current zoom (centred). */
    private fun cropForZoom(spec: CameraSpec) = zoom.crop(spec, highSpeed.isActive(spec))

    private fun onCaptureCompleted(
        request: CaptureRequest,
        result: TotalCaptureResult,
    ) {
        frameRate.onFrame(result, spec, activeResolution)
        if (!activeHighSpeed) {
            checkZoomHonored(request, result) // in high-speed mode the crop region may not apply — that is not zoom "clamping"
        }

        focus.onFrame(request, result)
    }

    /** If the camera clamps the zoom we offered (see [ZoomControl.verify]), the UI lowers the slider to what is achievable. */
    private fun checkZoomHonored(
        request: CaptureRequest,
        result: TotalCaptureResult,
    ) {
        val spec = spec ?: return
        val asked = request.get(CaptureRequest.SCALER_CROP_REGION)
        val applied = result.get(CaptureResult.SCALER_CROP_REGION)
        if (asked == null || applied == null) return

        val before = zoom.offeredMax(spec)
        val limit = zoom.verify(spec, asked.width(), applied.width()) ?: return

        Log.w(LOG_TAG, "Camera ${spec.id} crops the requested zoom; max ${before}x -> ${limit}x")
        val capabilities = buildCapabilities(spec)
        lastCapabilities = capabilities
        events.emit { it.onCapabilities(capabilities) }
        applyRepeating()
    }

    // =====================================================================
    // What the UI is told about the camera
    // =====================================================================

    private fun buildCapabilities(spec: CameraSpec): CameraCapabilities {
        val cameras = catalog.list()
        val fastConfig = if (highSpeed.isSupported(spec)) highSpeed.config(spec) else null
        return CameraCapabilities(
            camera = cameras.firstOrNull { it.id == spec.id } ?: CameraDescriptor(spec.id, spec.lens),
            availableCameras = cameras,
            resolutions = spec.resolutions,
            frameRates = spec.frameRates,
            highSpeedFps = fastConfig?.upper ?: 0,
            highSpeedResolution = fastConfig?.size,
            maxZoom = zoom.maxZoom(spec, highSpeed.isActive(spec)).toDouble(),
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

    private fun raiseStatus(status: CameraStatus?) = events.emit { it.onStatus(status) }

    // =====================================================================
    // What the parts of the controller get from it
    // =====================================================================

    private inner class Host : SessionHost {
        override fun activeCamera() = this@CameraController.activeCamera()

        override fun cropForZoom(spec: CameraSpec) = this@CameraController.cropForZoom(spec)

        override fun previewRequest(
            camera: ActiveCamera,
            afTrigger: Int,
        ): CaptureRequest = RequestFactory.preview(camera, afTrigger, requestParams(camera.spec)).build()

        override fun stillRequest(
            camera: ActiveCamera,
            target: Surface,
            jpegOrientation: Int,
        ): CaptureRequest = RequestFactory.still(camera, target, jpegOrientation, requestParams(camera.spec)).build()

        override val captureCallback: CameraCaptureSession.CaptureCallback
            get() = this@CameraController.captureCallback

        override fun applyRepeating() = this@CameraController.applyRepeating()
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
        private val isHighSpeed: Boolean,
        private val withStill: Boolean,
    ) : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) = onSessionConfigured(generation, session, resolution, isHighSpeed)

        override fun onConfigureFailed(session: CameraCaptureSession) =
            onSessionConfigureFailed(generation, resolution, isHighSpeed, withStill)
    }

    private inner class CaptureCb : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) = this@CameraController.onCaptureCompleted(request, result)
    }

    private companion object {
        const val MAX_RETRY_DELAY_MS = 8000L
        const val CAPABILITIES_FALLBACK_MS = 1500L

        /** If the UI never connected (the process was started not to show the screen) — we do not keep the camera. */
        const val WARM_UP_TIMEOUT_MS = 15_000L
    }
}
