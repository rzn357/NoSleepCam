package com.exciteddev.nosleepcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.exciteddev.nosleepcam.camera.CameraCapabilities
import com.exciteddev.nosleepcam.camera.CameraController
import com.exciteddev.nosleepcam.camera.CameraDescriptor
import com.exciteddev.nosleepcam.camera.CameraListener
import com.exciteddev.nosleepcam.camera.CameraPreviewView
import com.exciteddev.nosleepcam.camera.CameraResolution
import com.exciteddev.nosleepcam.camera.CameraStatus
import com.exciteddev.nosleepcam.camera.PhotoSaver
import com.exciteddev.nosleepcam.camera.PreviewSnapshot
import com.exciteddev.nosleepcam.ui.CameraOverlay
import com.exciteddev.nosleepcam.ui.CaptureAnimationView
import com.exciteddev.nosleepcam.ui.HighSpeedState
import com.exciteddev.nosleepcam.ui.SettingsPanel
import com.exciteddev.nosleepcam.ui.SettingsUiState
import com.exciteddev.nosleepcam.ui.message

/**
 * Single screen: full-screen camera with the zoom slider, flash and settings on top.
 * The camera keeps running while the app is open; the screen never turns off.
 *
 * It only wires the camera layer to the views (lifecycle, permissions, events); the decisions it makes are in [ControlRules].
 */
@Suppress("TooManyFunctions")
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var controller: CameraController
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout

    // Created later than the activity itself, so that the first camera frame appears as soon as possible.
    private var overlay: CameraOverlay? = null
    private var settingsPanel: SettingsPanel? = null

    private var appliedResolution: CameraResolution? = null
    private var capabilities: CameraCapabilities? = null
    private var selectedCameraId: String? = null
    private var status: CameraStatus? = null
    private var permissionNeeded = false
    private var permissionRequested = false
    private var statusToken = 0
    private var frameRate = 0
    private var zoomFactor = 1.0
    private var flashOn = false
    private var highSpeed = false // high-speed mode; deliberately not persisted between launches
    private var backCallback: Any? = null

    private lateinit var previewView: CameraPreviewView
    private var captureAnimation: CaptureAnimationView? = null
    private var capturing = false // capture animation in progress — new double taps are ignored
    private var thumbnailBeforeCapture: Bitmap? = null
    private var lastPhotoUri: Uri? = null

    /** In landscape the camera fills the whole screen (system bars hidden); in portrait it has black bars. */
    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Camera events arrive on a background thread — post them to the UI thread (the order is preserved). */
    private val cameraListener =
        object : CameraListener {
            override fun onCapabilities(capabilities: CameraCapabilities) {
                ui.post { onCapabilitiesChanged(capabilities) }
            }

            override fun onResolutionApplied(resolution: CameraResolution) {
                ui.post { handleResolutionApplied(resolution) }
            }

            override fun onStatus(status: CameraStatus?) {
                ui.post { onStatusChanged(status) }
            }

            override fun onFrameRate(fps: Int) {
                ui.post {
                    frameRate = fps
                    overlay?.setFrameRate(fps)
                }
            }

            override fun onPhotoSaved(uri: Uri) {
                ui.post {
                    lastPhotoUri = uri
                    prefs.lastPhoto = uri.toString()
                }
            }

            override fun onPhotoFailed() {
                ui.post { onPhotoFailedOnUi() }
            }
        }

    // ------------------------------------------------------------------
    // Lifecycle: the camera runs while the app is open
    // ------------------------------------------------------------------

    /** Applies the app language: the user's choice, else the system language if supported, else English. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTrace.mark { "MainActivity.onCreate: start" }
        super.onCreate(savedInstanceState)

        prefs = Prefs(this)
        controller = CameraController.getShared(this)

        // At startup the activity holds only the camera, so the first frame appears as soon as possible.
        // The controls (CameraOverlay) and the settings panel (SettingsPanel) are added later.
        // A touch on the frame sets focus at that point.
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        // Diagnostics: `am start --ef nsc_slow 10` slows the capture animation down 10 times (trace builds only).
        if (BuildConfig.STARTUP_TRACE) CaptureAnimationView.timeScale = intent.getFloatExtra("nsc_slow", 1f)

        previewView = CameraPreviewView(this, controller)
        previewView.onFirstFrame = { ensureOverlay() }
        previewView.onDoubleTapped = ::onDoubleTap
        root.addView(previewView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)

        SystemBarInsets.install(root) { isLandscape }
        applySystemBars()
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) overlay?.layoutFor(contentHeightDp())
        }

        // Restore the camera and the resolution. Zoom and flash are deliberately NOT persisted:
        // on every app launch zoom = 1x and the flash is off.
        val savedCamera = prefs.cameraId
        selectedCameraId = savedCamera
        controller.setResolution(savedCamera?.let(prefs::resolution))
        controller.setCameraId(savedCamera)
        controller.addListener(cameraListener)

        // Fallback: if no frame ever appears (camera error etc.), the controls must still show up.
        ui.postDelayed({ ensureOverlay() }, OVERLAY_FALLBACK_DELAY_MS)

        StartupTrace.mark { "MainActivity.onCreate: end" }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // After returning from another window or an edge swipe, the system bars must be hidden again.
        if (hasFocus) applySystemBars()
    }

    private fun applySystemBars() {
        SystemBarInsets.setImmersive(window, isLandscape)
        root.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        StartupTrace.mark { "MainActivity.onResume" }

        // The permission is requested only on the first show; later (returning from settings etc.) we only check it.
        startCamera(requestIfNeeded = !permissionRequested)
    }

    override fun onStop() {
        stopCamera()
        super.onStop()
    }

    override fun onDestroy() {
        controller.removeListener(cameraListener)
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Height of the content area in dp (without the system bars). */
    private fun contentHeightDp(): Double =
        (root.height - root.paddingTop - root.paddingBottom) / resources.displayMetrics.density.toDouble()

    // ------------------------------------------------------------------
    // Staged UI construction
    // ------------------------------------------------------------------

    /**
     * The controls are added after the first camera frame, not together with the activity:
     * showing the image needs only the TextureView, and the rest of the UI costs extra time.
     */
    private fun ensureOverlay(): CameraOverlay {
        overlay?.let { return it }

        StartupTrace.mark { "Creating controls: start" }
        val overlay = CameraOverlay(this)
        overlay.onZoomChanged = { zoom ->
            zoomFactor = zoom
            controller.setZoom(zoom)
        }
        overlay.onFlashClicked = ::onFlashClicked
        overlay.onSettingsClicked = ::onSettingsClicked
        overlay.onGrantClicked = { startCamera(requestIfNeeded = true) }
        overlay.onOpenSystemSettingsClicked = ::openSystemSettings
        overlay.onThumbnailClicked = ::openLastPhoto

        // Added before (below) the settings panel, if it already exists.
        val panel = settingsPanel
        if (panel != null) root.addView(overlay, root.indexOfChild(panel)) else root.addView(overlay)
        this.overlay = overlay

        // Catch up on everything that happened while the controls did not exist yet.
        capabilities?.let { overlay.setCapabilities(it, zoomFactor) }
        overlay.setFlash(flashOn)
        overlay.setHighSpeed(highSpeed)
        overlay.setFrameRate(frameRate)
        overlay.setStatus(status?.message(this))
        overlay.setPermissionPanelVisible(permissionNeeded)
        overlay.layoutFor(contentHeightDp())
        loadLastPhotoThumbnail()
        StartupTrace.mark { "Creating controls: end" }
        return overlay
    }

    // ------------------------------------------------------------------
    // Camera and permission
    // ------------------------------------------------------------------

    private fun startCamera(requestIfNeeded: Boolean) {
        val granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted && requestIfNeeded) {
            permissionRequested = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return // continue in onRequestPermissionsResult
        }

        StartupTrace.mark { "Camera permission checked (granted=$granted)" }

        permissionNeeded = !granted
        if (!granted) {
            // The permission prompt must be shown right away, without waiting for a frame that will never come.
            ensureOverlay()
            controller.setActive(false)
        }

        overlay?.setPermissionPanelVisible(!granted)
        if (!granted) return

        // The screen stays on while the app is open — otherwise the camera would "fall asleep" together with the screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.setActive(true)
    }

    private fun stopCamera() {
        controller.setActive(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) startCamera(requestIfNeeded = false)
        // Nothing to do for REQUEST_STORAGE: the user simply repeats the double tap.
    }

    // ------------------------------------------------------------------
    // Photo on double tap
    // ------------------------------------------------------------------

    private fun onDoubleTap() {
        StartupTrace.req {
            "double tap: capturing=$capturing hs=$highSpeed caps=${capabilities != null} canCapture=${previewView.canCapture}"
        }
        if (isDestroyed || capturing) return

        val needsStoragePermission =
            Build.VERSION.SDK_INT < 29 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        when (ControlRules.photoRequest(highSpeed, capabilities != null, previewView.canCapture, needsStoragePermission)) {
            ControlRules.PhotoRequest.Take -> {
                Unit
            }

            ControlRules.PhotoRequest.CameraNotReady -> {
                return
            }

            ControlRules.PhotoRequest.HighSpeed -> {
                showTransientStatus(getString(R.string.photo_unavailable_high_speed))
                return
            }

            ControlRules.PhotoRequest.Unavailable -> {
                showTransientStatus(getString(R.string.photo_unavailable))
                return
            }

            ControlRules.PhotoRequest.NeedsStoragePermission -> {
                showTransientStatus(getString(R.string.photo_storage_permission))
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
                return
            }
        }

        // The thumbnail circle must be laid out to know where to fly; a freshly created overlay gets laid out on the next frame.
        val overlay = ensureOverlay()
        val target = overlay.thumbnailBounds()
        if (target == null) {
            overlay.showThumbnailPlaceholder()
            root.post { onDoubleTap() }
            return
        }

        val snapshot = previewView.snapshot() ?: return
        capturing = true
        previewView.hideIndicator() // the first tap of the double tap has already shown the focus indicator
        controller.capturePhoto(previewView.displayRotation)
        playCaptureAnimation(snapshot, overlay.thumbnailBounds() ?: target)
    }

    /** Shutter "blink", then the photo shrinks and flies into the circle in the bottom-left corner. */
    private fun playCaptureAnimation(
        snapshot: PreviewSnapshot,
        target: RectF,
    ) {
        val overlay = overlay ?: return
        val animation =
            captureAnimation ?: CaptureAnimationView(this).also {
                captureAnimation = it
                // Above the camera and the controls, but below the settings panel.
                val panel = settingsPanel
                if (panel != null) root.addView(it, root.indexOfChild(panel)) else root.addView(it)
            }

        // Thumbnail for the circle: the central square of the photo.
        val inBitmap = snapshot.imageInBitmap
        val side = minOf(inBitmap.width(), inBitmap.height())
        val square =
            Bitmap.createBitmap(
                snapshot.bitmap,
                inBitmap.centerX() - side / 2,
                inBitmap.centerY() - side / 2,
                side,
                side,
            )

        thumbnailBeforeCapture = overlay.thumbnailImage
        animation.play(snapshot.bitmap, snapshot.imageInBitmap, snapshot.imageOnScreen, target) {
            overlay.setThumbnail(square, pop = true)
            capturing = false
        }
    }

    /** The photo could not be saved: put the previous photo back into the circle and tell the user. */
    private fun onPhotoFailedOnUi() {
        if (isDestroyed) return

        overlay?.setThumbnail(thumbnailBeforeCapture)
        showTransientStatus(getString(R.string.photo_failed))
    }

    /** Shows in the circle the last photo from the previous launch (read in the background so as not to slow down startup). */
    private fun loadLastPhotoThumbnail() {
        val saved = prefs.lastPhoto?.let(Uri::parse) ?: return
        lastPhotoUri = saved
        Thread {
            val bitmap = PhotoSaver.loadThumbnail(this, saved, THUMBNAIL_PX)
            ui.post {
                // If a new photo has been taken in the meantime, don't overwrite it.
                if (bitmap != null && !isDestroyed && !capturing && overlay?.thumbnailImage == null) overlay?.setThumbnail(bitmap)
            }
        }.start()
    }

    private fun openLastPhoto() {
        val uri = lastPhotoUri ?: return
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No app to view images — do nothing.
        }
    }

    private fun openSystemSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ------------------------------------------------------------------
    // Camera events (on the UI thread)
    // ------------------------------------------------------------------

    private fun onCapabilitiesChanged(caps: CameraCapabilities) {
        if (isDestroyed) return

        capabilities = caps
        selectedCameraId = caps.camera.id
        controller.setCameraId(caps.camera.id)

        // The camera (or its mode) no longer supports the high rate — turn the mode off.
        if (highSpeed && caps.highSpeedFps == 0) {
            highSpeed = false
            controller.setHighSpeed(false)
            overlay?.setHighSpeed(false)
        }

        // Zoom: from 1x to the camera's hardware maximum.
        val zoom = zoomFactor.coerceIn(caps.minZoom, maxOf(caps.minZoom, caps.maxZoom))
        if (zoom != zoomFactor) {
            zoomFactor = zoom
            controller.setZoom(zoom)
        }
        overlay?.setCapabilities(caps, zoom)

        if (isSettingsOpen) refreshSettings()
    }

    private fun handleResolutionApplied(resolution: CameraResolution) {
        if (isDestroyed) return

        appliedResolution = resolution
        controller.setResolution(resolution)

        if (isSettingsOpen) refreshSettings()
    }

    private fun onStatusChanged(newStatus: CameraStatus?) {
        if (isDestroyed) return

        status = newStatus

        // Show the problem message immediately, even if the controls have not been created yet.
        if (newStatus != null) ensureOverlay()

        overlay?.setStatus(newStatus?.message(this))
    }

    // ------------------------------------------------------------------
    // Flash
    // ------------------------------------------------------------------

    private fun onFlashClicked() {
        // The icon is always visible; if the flash cannot be used, explain why nothing happened.
        when (ControlRules.flashClick(capabilities?.supportsFlash, highSpeed)) {
            ControlRules.FlashClick.NoFlash -> {
                showTransientStatus(getString(R.string.flash_unavailable))
            }

            ControlRules.FlashClick.HighSpeed -> {
                showTransientStatus(getString(R.string.flash_unavailable_high_speed))
            }

            ControlRules.FlashClick.Toggle -> {
                flashOn = !flashOn
                controller.setFlash(flashOn)
                overlay?.setFlash(flashOn)
            }
        }
    }

    /** Short message for 2 seconds; then the normal state returns. */
    private fun showTransientStatus(text: String) {
        val token = ++statusToken
        overlay?.setStatus(text)
        ui.postDelayed({
            if (token == statusToken) overlay?.setStatus(status?.message(this))
        }, TRANSIENT_STATUS_MS)
    }

    // ------------------------------------------------------------------
    // Resolution settings
    // ------------------------------------------------------------------

    private val isSettingsOpen: Boolean get() = settingsPanel?.visibility == View.VISIBLE

    /** The settings panel is created only on the first open — this speeds up startup. */
    private fun ensureSettingsPanel(): SettingsPanel {
        settingsPanel?.let { return it }

        val panel = SettingsPanel(this)
        panel.visibility = View.GONE
        panel.onCloseRequested = ::closeSettings
        panel.onResolutionChosen = ::onResolutionChosen
        panel.onCameraChosen = ::onCameraChosen
        panel.onLanguageChosen = ::onLanguageChosen
        panel.onHighSpeedChanged = ::onHighSpeedChanged
        root.addView(panel)
        settingsPanel = panel
        return panel
    }

    private fun onSettingsClicked() {
        val panel = ensureSettingsPanel()
        refreshSettings()
        panel.visibility = View.VISIBLE
        setBackInterception(true)
    }

    private fun closeSettings() {
        settingsPanel?.visibility = View.GONE
        setBackInterception(false)
    }

    private fun refreshSettings() {
        val caps = capabilities
        settingsPanel?.bind(
            SettingsUiState(
                resolutions = caps?.resolutions.orEmpty(),
                frameRates = caps?.frameRates.orEmpty(),
                selectedResolution = appliedResolution,
                cameras = caps?.availableCameras.orEmpty(),
                selectedCameraId = selectedCameraId,
                pageHeightDp = contentHeightDp(),
                highSpeed =
                    caps
                        ?.takeIf { it.highSpeedFps > 0 }
                        ?.let { HighSpeedState(it.highSpeedFps, it.highSpeedResolution, highSpeed) },
            ),
        )
    }

    private fun onHighSpeedChanged(on: Boolean) {
        highSpeed = on
        controller.setHighSpeed(on)

        // A high-speed session has no flash (the request does not contain it) — turn it off and show that on the icon.
        if (on && flashOn) {
            flashOn = false
            controller.setFlash(false)
            overlay?.setFlash(false)
        }

        overlay?.setHighSpeed(on)
        refreshSettings()
    }

    private fun onResolutionChosen(resolution: CameraResolution) {
        controller.setResolution(resolution)
        selectedCameraId?.let { prefs.setResolution(it, resolution) }

        closeSettings()
    }

    private fun onLanguageChosen(tag: String) {
        if (tag == AppLanguage.effectiveTag(this)) return

        prefs.language = tag
        // Recreating the activity re-reads all resources in the new language; the camera reopens right after.
        recreate()
    }

    private fun onCameraChosen(camera: CameraDescriptor) {
        if (camera.id == selectedCameraId) return

        selectedCameraId = camera.id

        // Recall the last resolution chosen for this very camera (or null — it will be picked automatically).
        controller.setResolution(prefs.resolution(camera.id))
        controller.setCameraId(camera.id)
    }

    // ------------------------------------------------------------------
    // The "Back" button closes the settings panel
    // ------------------------------------------------------------------

    // On Android 13+ the "Back" gesture is handled by an OnBackInvokedCallback (see setBackInterception), and this method
    // remains for older versions, where it is the one that gets called.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isSettingsOpen) {
            closeSettings()
            return
        }

        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /**
     * Android 13+ does not call onBackPressed for the "Back" gesture — a separate handler is needed.
     * We register it only while the settings panel is open, so that otherwise "Back" works as usual.
     */
    private fun setBackInterception(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return

        if (enabled && backCallback == null) {
            val callback = OnBackInvokedCallback { closeSettings() }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        } else if (!enabled) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
    }

    private companion object {
        const val REQUEST_CAMERA = 1
        const val REQUEST_STORAGE = 2

        /** Gallery thumbnail size (px): a 40 dp circle ≈ 120 px, taken with a margin. */
        const val THUMBNAIL_PX = 160

        /** If the first camera frame never appears, the controls are added anyway after this time. */
        const val OVERLAY_FALLBACK_DELAY_MS = 1500L

        const val TRANSIENT_STATUS_MS = 2000L
    }
}
