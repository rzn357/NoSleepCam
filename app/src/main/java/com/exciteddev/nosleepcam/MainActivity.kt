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
import com.exciteddev.nosleepcam.camera.CameraCapabilities
import com.exciteddev.nosleepcam.camera.CameraController
import com.exciteddev.nosleepcam.camera.CameraDescriptor
import com.exciteddev.nosleepcam.camera.CameraListener
import com.exciteddev.nosleepcam.camera.CameraResolution
import com.exciteddev.nosleepcam.camera.CameraStatus
import com.exciteddev.nosleepcam.camera.PhotoSaver
import com.exciteddev.nosleepcam.ui.CameraOverlay
import com.exciteddev.nosleepcam.ui.CameraScreenState
import com.exciteddev.nosleepcam.ui.CaptureAnimationView
import com.exciteddev.nosleepcam.ui.SettingsPanel
import com.exciteddev.nosleepcam.ui.preview.CameraPreviewView
import com.exciteddev.nosleepcam.ui.preview.PreviewSnapshot
import java.util.concurrent.Executors

/**
 * Single screen: full-screen camera with the zoom slider, flash and settings on top.
 * The camera keeps running while the app is open; the screen never turns off.
 *
 * Data flows one way: an event (a camera callback, a tap, a permission result) replaces [state] through [update], and [render]
 * shows what changed — on the views and on the camera. The decisions it makes are in [ControlRules] and [CameraScreenState].
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor() // reading the gallery thumbnail
    private lateinit var controller: CameraController
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout

    // What the screen shows, and the part of it that has already been rendered (null — nothing yet).
    private var state = CameraScreenState()
    private var rendered: CameraScreenState? = null

    // Created later than the activity itself, so that the first camera frame appears as soon as possible.
    private var overlay: CameraOverlay? = null
    private var settingsPanel: SettingsPanel? = null

    private var permissionRequested = false
    private var backInterception: BackInterception? = null // API 33+ only, see setBackInterception
    private val clearTransientMessage = Runnable { update { it.copy(transientMessage = null) } }

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
                ui.post { update { it.copy(frameRate = fps) } }
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
        controller = (application as NoSleepCamApp).cameraController

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
        // on every app launch zoom = 1x and the flash is off (the first render hands that to the camera).
        val savedCamera = prefs.cameraId
        state = state.copy(selectedCameraId = savedCamera)
        render()
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
        background.shutdown()
        super.onDestroy()
    }

    /** Height of the content area in dp (without the system bars). */
    private fun contentHeightDp(): Double =
        (root.height - root.paddingTop - root.paddingBottom) / resources.displayMetrics.density.toDouble()

    // ------------------------------------------------------------------
    // State → views and camera
    // ------------------------------------------------------------------

    /** Replaces the state with the result of [change] and shows the difference. */
    private fun update(change: (CameraScreenState) -> CameraScreenState) {
        val next = change(state)
        if (next == state) return

        state = next
        render()
    }

    /** Brings the camera, the controls and the settings panel in line with [state]; only what changed since the last call. */
    private fun render() {
        val previous = rendered
        val current = state
        rendered = current

        syncCamera(previous, current)
        overlay?.render(previous, current)
        renderSettings(previous, current)
    }

    /** The camera takes zoom, flash and high-speed mode from the state, whoever changed them. */
    private fun syncCamera(
        previous: CameraScreenState?,
        current: CameraScreenState,
    ) {
        if (previous?.zoom != current.zoom) controller.setZoom(current.zoom)
        if (previous?.flashOn != current.flashOn) controller.setFlash(current.flashOn)
        if (previous?.highSpeed != current.highSpeed) controller.setHighSpeed(current.highSpeed)
    }

    private fun renderSettings(
        previous: CameraScreenState?,
        current: CameraScreenState,
    ) {
        val wasOpen = previous?.settingsOpen == true
        if (current.settingsOpen != wasOpen) {
            if (current.settingsOpen) {
                val panel = ensureSettingsPanel()
                panel.bind(current.settingsUiState(contentHeightDp()))
                panel.visibility = View.VISIBLE
            } else {
                settingsPanel?.visibility = View.GONE
            }
            setBackInterception(current.settingsOpen)
            return
        }

        if (previous == null || !current.settingsOpen) return

        val changed =
            previous.capabilities !== current.capabilities ||
                previous.appliedResolution != current.appliedResolution ||
                previous.highSpeed != current.highSpeed
        if (changed) settingsPanel?.bind(current.settingsUiState(contentHeightDp()))
    }

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
        overlay.onZoomChanged = { zoom -> update { it.copy(zoom = zoom) } }
        overlay.onFlashClicked = ::onFlashClicked
        overlay.onSettingsClicked = { update { it.copy(settingsOpen = true) } }
        overlay.onGrantClicked = { startCamera(requestIfNeeded = true) }
        overlay.onOpenSystemSettingsClicked = ::openSystemSettings
        overlay.onThumbnailClicked = ::openLastPhoto

        // Added before (below) the settings panel, if it already exists.
        val panel = settingsPanel
        if (panel != null) root.addView(overlay, root.indexOfChild(panel)) else root.addView(overlay)
        this.overlay = overlay

        // Catch up on everything that happened while the controls did not exist yet.
        overlay.render(null, state)
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

        update { it.copy(permissionNeeded = !granted) }
        if (!granted) {
            // The permission prompt must be shown right away, without waiting for a frame that will never come.
            ensureOverlay()
            controller.setActive(false)
            return
        }

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
            "double tap: capturing=$capturing hs=${state.highSpeed} caps=${state.capabilities != null} canCapture=${previewView.canCapture}"
        }
        if (isDestroyed || capturing) return

        val needsStoragePermission =
            Build.VERSION.SDK_INT < 29 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        val request =
            ControlRules.photoRequest(state.highSpeed, state.capabilities != null, previewView.canCapture, needsStoragePermission)
        when (request) {
            ControlRules.PhotoRequest.Take -> {
                takePhoto()
            }

            ControlRules.PhotoRequest.CameraNotReady -> {
                Unit
            }

            ControlRules.PhotoRequest.HighSpeed -> {
                showTransientStatus(getString(R.string.photo_unavailable_high_speed))
            }

            ControlRules.PhotoRequest.Unavailable -> {
                showTransientStatus(getString(R.string.photo_unavailable))
            }

            ControlRules.PhotoRequest.NeedsStoragePermission -> {
                showTransientStatus(getString(R.string.photo_storage_permission))
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
            }
        }
    }

    private fun takePhoto() {
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
        background.execute {
            val bitmap = PhotoSaver.loadThumbnail(this, saved, THUMBNAIL_PX)
            ui.post {
                // If a new photo has been taken in the meantime, don't overwrite it.
                if (bitmap != null && !isDestroyed && !capturing && overlay?.thumbnailImage == null) overlay?.setThumbnail(bitmap)
            }
        }
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

        controller.setCameraId(caps.camera.id)
        // Remember the camera that is really open (it differs from the saved one if that one disappeared).
        if (prefs.cameraId != caps.camera.id) prefs.cameraId = caps.camera.id
        update { it.withCapabilities(caps) }
    }

    private fun handleResolutionApplied(resolution: CameraResolution) {
        if (isDestroyed) return

        controller.setResolution(resolution)
        update { it.copy(appliedResolution = resolution) }
    }

    private fun onStatusChanged(newStatus: CameraStatus?) {
        if (isDestroyed) return

        update { it.copy(cameraStatus = newStatus) }

        // Show the problem message immediately, even if the controls have not been created yet.
        if (newStatus != null) ensureOverlay()
    }

    // ------------------------------------------------------------------
    // Flash
    // ------------------------------------------------------------------

    private fun onFlashClicked() {
        // The icon is always visible; if the flash cannot be used, explain why nothing happened.
        when (ControlRules.flashClick(state.capabilities?.supportsFlash, state.highSpeed)) {
            ControlRules.FlashClick.NoFlash -> {
                showTransientStatus(getString(R.string.flash_unavailable))
            }

            ControlRules.FlashClick.HighSpeed -> {
                showTransientStatus(getString(R.string.flash_unavailable_high_speed))
            }

            ControlRules.FlashClick.Toggle -> {
                update { it.copy(flashOn = !it.flashOn) }
            }
        }
    }

    /** Short message for 2 seconds; then the normal state returns. */
    private fun showTransientStatus(text: String) {
        ui.removeCallbacks(clearTransientMessage)
        update { it.copy(transientMessage = text) }
        ui.postDelayed(clearTransientMessage, TRANSIENT_STATUS_MS)
    }

    // ------------------------------------------------------------------
    // Resolution settings
    // ------------------------------------------------------------------

    /** The settings panel is created only on the first open — this speeds up startup. */
    private fun ensureSettingsPanel(): SettingsPanel {
        settingsPanel?.let { return it }

        val panel = SettingsPanel(this)
        panel.visibility = View.GONE
        panel.onCloseRequested = ::closeSettings
        panel.onResolutionChosen = ::onResolutionChosen
        panel.onCameraChosen = ::onCameraChosen
        panel.onLanguageChosen = ::onLanguageChosen
        panel.onHighSpeedChanged = { on -> update { it.withHighSpeed(on) } }
        root.addView(panel)
        settingsPanel = panel
        return panel
    }

    private fun closeSettings() {
        update { it.copy(settingsOpen = false) }
    }

    private fun onResolutionChosen(resolution: CameraResolution) {
        controller.setResolution(resolution)
        state.selectedCameraId?.let { prefs.setResolution(it, resolution) }

        closeSettings()
    }

    private fun onLanguageChosen(tag: String) {
        if (tag == AppLanguage.effectiveTag(this)) return

        prefs.language = tag
        // Recreating the activity re-reads all resources in the new language; the camera reopens right after.
        recreate()
    }

    private fun onCameraChosen(camera: CameraDescriptor) {
        if (camera.id == state.selectedCameraId) return

        update { it.copy(selectedCameraId = camera.id) }

        // Recall the last resolution chosen for this very camera (or null — it will be picked automatically).
        controller.setResolution(prefs.resolution(camera.id))
        controller.setCameraId(camera.id)
    }

    // ------------------------------------------------------------------
    // The "Back" button closes the settings panel
    // ------------------------------------------------------------------

    // On Android 13+ the "Back" gesture goes through setBackInterception; this method serves older versions.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (state.settingsOpen) {
            closeSettings()
            return
        }

        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /** Android 13+ needs a registered callback for the "Back" gesture (see [BackInterception]); older systems use [onBackPressed]. */
    private fun setBackInterception(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return

        val interception = backInterception ?: BackInterception(this, ::closeSettings).also { backInterception = it }
        interception.setEnabled(enabled)
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
