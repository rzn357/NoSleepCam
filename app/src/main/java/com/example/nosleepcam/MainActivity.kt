package com.example.nosleepcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
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
import com.example.nosleepcam.camera.CameraCapabilities
import com.example.nosleepcam.camera.CameraController
import com.example.nosleepcam.camera.CameraDescriptor
import com.example.nosleepcam.camera.CameraListener
import com.example.nosleepcam.camera.CameraPreviewView
import com.example.nosleepcam.camera.CameraResolution
import com.example.nosleepcam.camera.PhotoSaver
import com.example.nosleepcam.camera.PreviewSnapshot
import com.example.nosleepcam.ui.CaptureAnimationView
import com.example.nosleepcam.ui.CameraOverlay
import com.example.nosleepcam.ui.SettingsPanel

/**
 * Єдиний екран: камера на весь екран, а поверх — повзунок зуму, спалах і налаштування.
 * Камера працює, доки додаток відкритий; екран не гасне.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var controller: CameraController
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout

    // Створюються пізніше, ніж сама активність — щоб перший кадр камери з'явився якнайшвидше.
    private var overlay: CameraOverlay? = null
    private var settingsPanel: SettingsPanel? = null

    private var resolutions: List<CameraResolution> = emptyList()
    private var cameras: List<CameraDescriptor> = emptyList()
    private var appliedResolution: CameraResolution? = null
    private var capabilities: CameraCapabilities? = null
    private var selectedCameraId: String? = null
    private var status: String? = null
    private var permissionNeeded = false
    private var permissionRequested = false
    private var statusToken = 0
    private var frameRate = 0
    private var zoomFactor = 1.0
    private var flashOn = false
    private var highSpeed = false // високошвидкісний режим; навмисно не зберігається між запусками
    private var backCallback: Any? = null

    private lateinit var previewView: CameraPreviewView
    private var captureAnimation: CaptureAnimationView? = null
    private var capturing = false             // анімація знімка триває — нові подвійні тапи ігноруємо
    private var thumbnailBeforeCapture: Bitmap? = null
    private var lastPhotoUri: Uri? = null

    /** У ландшафті камера на весь екран (системні панелі сховано), у портреті — із чорними панелями. */
    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Події камери приходять із фонового потоку — переводимо їх в UI-потік (порядок зберігається). */
    private val cameraListener = object : CameraListener {
        override fun onCapabilities(capabilities: CameraCapabilities) {
            ui.post { onCapabilitiesChanged(capabilities) }
        }

        override fun onResolutionApplied(resolution: CameraResolution) {
            ui.post { handleResolutionApplied(resolution) }
        }

        override fun onStatus(status: String?) {
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
    // Життєвий цикл: камера працює, доки додаток відкритий
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTrace.mark { "MainActivity.onCreate: початок" }
        super.onCreate(savedInstanceState)

        prefs = Prefs(this)
        controller = CameraController.getShared(this)

        // На старті в активності лише камера: так перший кадр з'являється якнайшвидше.
        // Елементи керування (CameraOverlay) та вікно налаштувань (SettingsPanel) додаються пізніше.
        // Дотик по кадру — фокус у цій точці.
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        // Діагностика: `am start --ef nsc_slow 10` уповільнює анімацію знімка у 10 разів (лише у збірці з трасуванням).
        if (BuildConfig.STARTUP_TRACE) CaptureAnimationView.timeScale = intent.getFloatExtra("nsc_slow", 1f)

        previewView = CameraPreviewView(this)
        previewView.onFirstFrame = { ensureOverlay() }
        previewView.onDoubleTapped = ::onDoubleTap
        root.addView(previewView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)

        SystemBarInsets.install(root) { isLandscape }
        applySystemBars()
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) overlay?.layoutFor(contentHeightDp())
        }

        // Відновлюємо камеру та роздільність. Зум і спалах навмисно НЕ зберігаються:
        // при кожному запуску додатка зум = 1x, спалах вимкнений.
        val savedCamera = prefs.cameraId
        selectedCameraId = savedCamera
        controller.setResolution(savedCamera?.let(prefs::resolution))
        controller.setCameraId(savedCamera)
        controller.addListener(cameraListener)

        // Запасний варіант: якщо кадр не з'явиться (помилка камери тощо), керування все одно має зʼявитись.
        ui.postDelayed({ ensureOverlay() }, OVERLAY_FALLBACK_DELAY_MS)

        StartupTrace.mark { "MainActivity.onCreate: кінець" }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Після повернення з іншого вікна або свайпу від краю системні панелі треба сховати знову.
        if (hasFocus) applySystemBars()
    }

    private fun applySystemBars() {
        SystemBarInsets.setImmersive(window, isLandscape)
        root.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        StartupTrace.mark { "MainActivity.onResume" }

        // Дозвіл просимо лише при першому показі; далі (повернення з налаштувань тощо) — лише перевіряємо.
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

    /** Висота області вмісту в dp (без системних панелей). */
    private fun contentHeightDp(): Double =
        (root.height - root.paddingTop - root.paddingBottom) / resources.displayMetrics.density.toDouble()

    // ------------------------------------------------------------------
    // Поетапне створення інтерфейсу
    // ------------------------------------------------------------------

    /**
     * Елементи керування додаємо після першого кадра камери, а не разом з активністю:
     * для появи зображення потрібен лише TextureView, а решта інтерфейсу коштує зайвий час.
     */
    private fun ensureOverlay(): CameraOverlay {
        overlay?.let { return it }

        StartupTrace.mark { "Створення елементів керування: початок" }
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

        // Додаємо перед (під) вікном налаштувань, якщо воно вже існує.
        val panel = settingsPanel
        if (panel != null) root.addView(overlay, root.indexOfChild(panel)) else root.addView(overlay)
        this.overlay = overlay

        // Підтягуємо все, що сталось, поки елементів ще не було.
        capabilities?.let { overlay.setCapabilities(it, zoomFactor) }
        overlay.setFlash(flashOn)
        overlay.setHighSpeed(highSpeed)
        overlay.setFrameRate(frameRate)
        overlay.setStatus(status)
        overlay.setPermissionPanelVisible(permissionNeeded)
        overlay.layoutFor(contentHeightDp())
        loadLastPhotoThumbnail()
        StartupTrace.mark { "Створення елементів керування: кінець" }
        return overlay
    }

    // ------------------------------------------------------------------
    // Камера та дозвіл
    // ------------------------------------------------------------------

    private fun startCamera(requestIfNeeded: Boolean) {
        val granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted && requestIfNeeded) {
            permissionRequested = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return // продовжимо в onRequestPermissionsResult
        }

        StartupTrace.mark { "Дозвіл на камеру перевірено (granted=$granted)" }

        permissionNeeded = !granted
        if (!granted) {
            // Запит дозволу треба показати одразу, не чекаючи на кадр, якого не буде.
            ensureOverlay()
            controller.setActive(false)
        }

        overlay?.setPermissionPanelVisible(!granted)
        if (!granted) return

        // Екран не гасне, поки відкритий додаток — інакше камера «засне» разом з екраном.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.setActive(true)
    }

    private fun stopCamera() {
        controller.setActive(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) startCamera(requestIfNeeded = false)
        // Для REQUEST_STORAGE нічого не робимо: користувач просто повторює подвійний тап.
    }

    // ------------------------------------------------------------------
    // Знімок по подвійному тапу
    // ------------------------------------------------------------------

    private fun onDoubleTap() {
        StartupTrace.req { "double tap: capturing=$capturing hs=$highSpeed caps=${capabilities != null} canCapture=${previewView.canCapture}" }
        if (isDestroyed || capturing) return

        if (highSpeed) {
            showTransientStatus(getString(R.string.photo_unavailable_high_speed))
            return
        }
        if (capabilities == null) return // камера ще не готова
        if (!previewView.canCapture) {
            showTransientStatus(getString(R.string.photo_unavailable))
            return
        }
        // До Android 10 писати в галерею можна лише з дозволом на пам'ять.
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            showTransientStatus(getString(R.string.photo_storage_permission))
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
            return
        }

        // Коло-мініатюра має бути розкладене, щоб знати, куди летіти; щойно створений overlay розкладеться на наступному кадрі.
        val overlay = ensureOverlay()
        val target = overlay.thumbnailBounds()
        if (target == null) {
            overlay.showThumbnailPlaceholder()
            root.post { onDoubleTap() }
            return
        }

        val snapshot = previewView.snapshot() ?: return
        capturing = true
        previewView.hideIndicator() // перший тап подвійного вже показав індикатор фокусу
        controller.capturePhoto(previewView.displayRotation)
        playCaptureAnimation(snapshot, overlay.thumbnailBounds() ?: target)
    }

    /** «Блимання» затвора, а потім знімок зменшується й летить у коло в лівому нижньому куті. */
    private fun playCaptureAnimation(snapshot: PreviewSnapshot, target: RectF) {
        val overlay = overlay ?: return
        val animation = captureAnimation ?: CaptureAnimationView(this).also {
            captureAnimation = it
            // Над камерою й керуванням, але під вікном налаштувань.
            val panel = settingsPanel
            if (panel != null) root.addView(it, root.indexOfChild(panel)) else root.addView(it)
        }

        // Мініатюра для кола: центральний квадрат знімка.
        val inBitmap = snapshot.imageInBitmap
        val side = minOf(inBitmap.width(), inBitmap.height())
        val square = Bitmap.createBitmap(
            snapshot.bitmap, inBitmap.centerX() - side / 2, inBitmap.centerY() - side / 2, side, side,
        )

        thumbnailBeforeCapture = overlay.thumbnailImage
        animation.play(snapshot.bitmap, snapshot.imageInBitmap, snapshot.imageOnScreen, target) {
            overlay.setThumbnail(square, pop = true)
            capturing = false
        }
    }

    /** Знімок не вдалося зберегти: повертаємо в коло попередній знімок і кажемо про це. */
    private fun onPhotoFailedOnUi() {
        if (isDestroyed) return

        overlay?.setThumbnail(thumbnailBeforeCapture)
        showTransientStatus(getString(R.string.photo_failed))
    }

    /** Показує в колі останній знімок попереднього запуску (читається у фоні, щоб не гальмувати старт). */
    private fun loadLastPhotoThumbnail() {
        val saved = prefs.lastPhoto?.let(Uri::parse) ?: return
        lastPhotoUri = saved
        Thread {
            val bitmap = PhotoSaver.loadThumbnail(this, saved, THUMBNAIL_PX)
            ui.post {
                // Якщо за цей час уже зроблено новий знімок, його не затираємо.
                if (bitmap != null && !isDestroyed && !capturing && overlay?.thumbnailImage == null) overlay?.setThumbnail(bitmap)
            }
        }.start()
    }

    private fun openLastPhoto() {
        val uri = lastPhotoUri ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Немає програми для перегляду зображень — нічого не робимо.
        }
    }

    private fun openSystemSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ------------------------------------------------------------------
    // Події камери (в UI-потоці)
    // ------------------------------------------------------------------

    private fun onCapabilitiesChanged(caps: CameraCapabilities) {
        if (isDestroyed) return

        capabilities = caps
        resolutions = caps.resolutions
        cameras = caps.availableCameras
        selectedCameraId = caps.camera.id
        controller.setCameraId(caps.camera.id)

        // Камера (або її режим) високу частоту більше не підтримує — вимикаємо режим.
        if (highSpeed && caps.highSpeedFps == 0) {
            highSpeed = false
            controller.setHighSpeed(false)
            overlay?.setHighSpeed(false)
        }

        // Запам'ятовуємо камеру, з якою працюємо, щоб при наступному запуску відновити її та обрану для неї роздільність.
        prefs.cameraId = caps.camera.id

        // Зум: від 1x до апаратного максимуму камери.
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

    private fun onStatusChanged(newStatus: String?) {
        if (isDestroyed) return

        status = newStatus

        // Повідомлення про проблему показуємо одразу, навіть якщо керування ще не створено.
        if (!newStatus.isNullOrEmpty()) ensureOverlay()

        overlay?.setStatus(newStatus)
    }

    // ------------------------------------------------------------------
    // Спалах
    // ------------------------------------------------------------------

    private fun onFlashClicked() {
        // Іконка видима завжди; якщо в цієї камери спалаху немає — пояснюємо, чому нічого не відбулось.
        if (capabilities?.supportsFlash == false) {
            showTransientStatus(getString(R.string.flash_unavailable))
            return
        }

        if (highSpeed) {
            showTransientStatus(getString(R.string.flash_unavailable_high_speed))
            return
        }

        flashOn = !flashOn
        controller.setFlash(flashOn)
        overlay?.setFlash(flashOn)
    }

    /** Коротке повідомлення на 2 секунди; потім повертаємо звичайний стан. */
    private fun showTransientStatus(text: String) {
        val token = ++statusToken
        overlay?.setStatus(text)
        ui.postDelayed({
            if (token == statusToken) overlay?.setStatus(status)
        }, TRANSIENT_STATUS_MS)
    }

    // ------------------------------------------------------------------
    // Налаштування роздільної здатності
    // ------------------------------------------------------------------

    private val isSettingsOpen: Boolean get() = settingsPanel?.visibility == View.VISIBLE

    /** Вікно налаштувань створюється лише при першому відкритті — це прискорює запуск. */
    private fun ensureSettingsPanel(): SettingsPanel {
        settingsPanel?.let { return it }

        val panel = SettingsPanel(this)
        panel.visibility = View.GONE
        panel.onCloseRequested = ::closeSettings
        panel.onResolutionChosen = ::onResolutionChosen
        panel.onCameraChosen = ::onCameraChosen
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
        settingsPanel?.bind(
            resolutions, capabilities?.frameRates, appliedResolution, cameras, selectedCameraId, contentHeightDp(),
            capabilities?.highSpeedFps ?: 0, capabilities?.highSpeedResolution, highSpeed,
        )
    }

    private fun onHighSpeedChanged(on: Boolean) {
        highSpeed = on
        controller.setHighSpeed(on)

        // У високошвидкісній сесії спалаху немає (запит його не містить) — вимикаємо й показуємо це на іконці.
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

    private fun onCameraChosen(camera: CameraDescriptor) {
        if (camera.id == selectedCameraId) return

        prefs.cameraId = camera.id
        selectedCameraId = camera.id

        // Згадуємо останню роздільність, обрану саме для цієї камери (або null — підбере автоматично).
        controller.setResolution(prefs.resolution(camera.id))
        controller.setCameraId(camera.id)
    }

    // ------------------------------------------------------------------
    // Кнопка «Назад» закриває вікно налаштувань
    // ------------------------------------------------------------------

    // Для Android 13+ жест «Назад» обробляє OnBackInvokedCallback (див. setBackInterception), а цей метод
    // лишається для старіших версій, де він і викликається.
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
     * Android 13+ з жестом «Назад» не викликає onBackPressed — потрібен окремий обробник.
     * Реєструємо його лише поки вікно налаштувань відкрите, щоб інакше «Назад» працювало як звичайно.
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

        /** Розмір мініатюри з галереї (px): коло 40 dp ≈ 120 px, беремо із запасом. */
        const val THUMBNAIL_PX = 160

        /** Якщо перший кадр камери так і не з'явився, елементи керування все одно додаємо за цей час. */
        const val OVERLAY_FALLBACK_DELAY_MS = 1500L

        const val TRANSIENT_STATUS_MS = 2000L
    }
}
