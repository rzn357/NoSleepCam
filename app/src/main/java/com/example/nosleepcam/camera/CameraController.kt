package com.example.nosleepcam.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.example.nosleepcam.Prefs
import com.example.nosleepcam.R
import com.example.nosleepcam.StartupTrace
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.roundToInt

private const val TAG = "NoSleepCam"

/** Геометрія буфера попереднього перегляду — потрібна, щоб правильно повернути й вписати зображення. */
internal data class PreviewGeometry(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val sensorOrientation: Int,
    val isFront: Boolean,
    val canFocus: Boolean,

    /** Діапазон індексів експокомпенсації та крок у EV; evMin == evMax — регулювання яскравості недоступне. */
    val evMin: Int = 0,
    val evMax: Int = 0,
    val evStep: Float = 0f,

    /** У сесії є JPEG-потік для знімків (немає у високошвидкісному режимі й коли пристрій відхилив такий потік). */
    val canCapture: Boolean = false,
) {
    val isValid: Boolean get() = bufferWidth > 0 && bufferHeight > 0

    val canAdjustExposure: Boolean get() = evMax > evMin

    /** Дотик по кадру має сенс: або фокус, або хоча б вимірювання експозиції та яскравість. */
    val canTap: Boolean get() = canFocus || canAdjustExposure
}

/** [Metered] — камера без автофокусу: у цій точці лише виміряно експозицію. */
internal enum class FocusStatus { Searching, Focused, Failed, Metered }

/**
 * Підписник на події камери. Усі методи викликаються на фоновому потоці камери —
 * споживач сам переходить у UI-потік.
 */
internal interface CameraListener {
    fun onCapabilities(capabilities: CameraCapabilities) {}
    fun onResolutionApplied(resolution: CameraResolution) {}
    fun onGeometry(geometry: PreviewGeometry) {}

    /** Текст стану для користувача; null — усе гаразд, повідомлення ховається. */
    fun onStatus(status: String?) {}
    fun onFocus(status: FocusStatus) {}

    /** Фактична частота кадрів (к/с, ціле); 0 — камера не працює або йде перенастроювання. */
    fun onFrameRate(fps: Int) {}

    /** Знімок записано в галерею. Викликається не з потоку камери. */
    fun onPhotoSaved(uri: Uri) {}

    /** Знімок не вдалося зробити чи зберегти. */
    fun onPhotoFailed() {}
}

/**
 * Керує камерою через Camera2: відкриття, сесія попереднього перегляду, зум, автофокус
 * (безперервний + по дотику) і автоматичне перепідключення.
 *
 * Усі зміни стану камери виконуються на одному фоновому потоці ([handler]).
 * Зовнішні методи лише запам'ятовують «бажаний стан» і викликають [reconcile],
 * який приводить фактичний стан камери до бажаного — тому порядок викликів не має значення.
 */
internal class CameraController private constructor(private val context: Context) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("NoSleepCam.Camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val retryRunnable = Runnable { reconcile() }
    private val captureCallback = CaptureCb()
    private val listeners = CopyOnWriteArrayList<CameraListener>()

    // Кеші та чорні списки — лише потік камери.
    private val specs = HashMap<String, CameraSpec>()
    private val blocked = HashSet<Pair<String, CameraResolution>>()
    private val blockedHighSpeed = HashSet<Pair<String, CameraResolution>>()

    // ---- Бажаний стан (пишеться з будь-якого потоку) ----
    @Volatile private var wantActive = false
    @Volatile private var wantCameraId: String? = null
    @Volatile private var wantResolution: CameraResolution? = null
    @Volatile private var wantZoom = 1f
    @Volatile private var wantFlash = false
    @Volatile private var wantHighSpeed = false
    @Volatile private var wantEv = 0 // індекс експокомпенсації (яскравість); скидається новим дотиком
    private val zoomPosted = AtomicBoolean(false)

    // ---- Фактичний стан (лише потік камери) ----
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
    private var activeHighSpeed = false     // поточна сесія — високошвидкісна
    private var publishedHighSpeed = false  // з яким режимом востаннє передано можливості інтерфейсу

    // ---- Фокус по дотику (лише потік камери) ----
    private enum class FocusPhase { None, Triggering, Searching }

    private var focusPhase = FocusPhase.None

    /**
     * Поки true — усі запити йдуть у режимі AF_MODE_AUTO. У CONTINUOUS_PICTURE драйвер Honor 10 на тригер START лише
     * «замикає» поточну позицію лінзи за ~4 кадри й нічого не скануючи (LENS_FOCUS_DISTANCE не змінюється), тож фокус по
     * дотику не працює. В AUTO той самий тригер запускає справжній ACTIVE_SCAN по вибраному регіону.
     */
    private var focusAutoMode = false
    private var focusStartFrame = 0L
    private var focusToken = 0
    private var afRegions: Array<MeteringRectangle>? = null
    private var aeRegions: Array<MeteringRectangle>? = null

    private var lastCapabilities: CameraCapabilities? = null

    // ---- Знімки: JPEG-потік у тій самій сесії, що й попередній перегляд (лише потік камери, окрім savePool) ----
    private var reader: ImageReader? = null          // потік активної сесії
    private var pendingReader: ImageReader? = null   // потік сесії, що налаштовується
    private val blockedStill = HashSet<Pair<String, CameraResolution>>()
    private val savePool = Executors.newSingleThreadExecutor()
    private val stillCallback = StillCb()
    private var pendingCaptureRotation = -1  // знімок чекає на завершення фокусу (−1 — не чекає)
    private val pendingCaptureRunnable = Runnable { flushPendingCapture() }
    private var capabilitiesPending = false  // камеру відкрито, а можливості ще не передано інтерфейсу
    private val publishPendingRunnable = Runnable { publishPendingCapabilities() }
    @Volatile private var uiAttached = false

    // =====================================================================
    // Публічний API (потокобезпечний)
    // =====================================================================

    /**
     * Підписує інтерфейс і одразу надсилає йому все, що відбулось ще до підписки
     * (камера могла відкритись під час прогріву, коли підписників ще не було).
     */
    fun addListener(listener: CameraListener) = post {
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

        // Повзунок генерує десятки подій на секунду — об'єднуємо їх в одне оновлення запиту.
        if (zoomPosted.compareAndSet(false, true)) {
            post {
                zoomPosted.set(false)
                applyRepeating()
            }
        }
    }

    /** Спалах у режимі ліхтарика: світиться постійно, доки камера працює. */
    fun setFlash(on: Boolean) {
        if (wantFlash == on) return
        wantFlash = on
        post(::applyRepeating)
    }

    /** Яскравість: індекс експокомпенсації (діапазон і крок — у [PreviewGeometry]); обмежується діапазоном камери. */
    fun setExposureCompensation(index: Int) {
        if (wantEv == index) return
        wantEv = index
        post(::applyRepeating)
    }

    /**
     * Високошвидкісний режим (до 120 кадрів/с). Вмикається лише якщо камера його заявляє; інакше ігнорується.
     * Сесія перебудовується, а зум обмежується заявленим максимумом.
     */
    fun setHighSpeed(on: Boolean) {
        if (wantHighSpeed == on) return
        wantHighSpeed = on
        post(::reconcile)
    }

    fun onSurfaceAvailable(texture: SurfaceTexture) = post {
        StartupTrace.mark { "Поверхню TextureView готово" }
        this.texture = texture
        reconcile()
    }

    fun onSurfaceDestroyed(texture: SurfaceTexture) = post {
        if (this.texture === texture) {
            closeCamera()
            this.texture = null
        }

        // Контролер сам звільняє текстуру, тому що TextureView ми попросили її не вивільняти:
        // так камера гарантовано закривається ДО знищення поверхні.
        try {
            texture.release()
        } catch (ex: Exception) {
            Log.w(TAG, "Texture release: ${ex.message}")
        }
    }

    /** Фокус у точці (s, t) ∈ [0,1]² «природного» (як у телефона в портреті) зображення. */
    fun focusAt(s: Float, t: Float) = post { startFocus(s, t) }

    fun resetFocus() = post {
        afRegions = null
        aeRegions = null
        endFocusLock()
    }

    private fun post(action: () -> Unit) {
        handler.post(action)
    }

    // =====================================================================
    // Зведення бажаного стану до фактичного
    // =====================================================================

    private fun reconcile() {
        if (!wantActive) {
            closeCamera()
            return
        }

        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            closeCamera()
            raiseStatus(context.getString(R.string.status_no_permission))
            return
        }

        val targetId = resolveTargetId(wantCameraId)
        if (targetId == null) {
            raiseStatus(context.getString(R.string.status_no_cameras))
            return
        }

        if (device != null && device?.id != targetId) closeCamera()

        // Пристрій камери відкриваємо одразу, не чекаючи на поверхню: це триває найдовше,
        // і воно має йти паралельно зі створенням інтерфейсу.
        if (device == null) {
            if (!opening) openCamera(targetId)
            return // продовжимо в onCameraOpened
        }

        if (texture == null) return // камеру відкрито; сесію створимо, щойно з'явиться поверхня (onSurfaceAvailable)

        if (sessionPending) return // продовжимо в onSessionConfigured / onSessionConfigureFailed

        // Високошвидкісний режим: розмір кадру диктує камера, вибрана користувачем роздільність не чіпається.
        val currentSpec = spec
        val highSpeed = if (currentSpec != null && highSpeedWanted(currentSpec)) resolveHighSpeed(currentSpec) else null

        val resolution = highSpeed?.size ?: resolveResolution()
        if (resolution == null) {
            // Усі розміри відхилені пристроєм — очищаємо чорний список і пробуємо знову пізніше.
            blocked.clear()
            raiseStatus(context.getString(R.string.status_start_failed))
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

    /** Перший високошвидкісний розмір, який пристрій ще не відхилив (найбільший — він і найкращий). */
    private fun resolveHighSpeed(spec: CameraSpec): HighSpeedConfig? =
        spec.highSpeedConfigs.firstOrNull { (spec.id to it.size) !in blockedHighSpeed }

    /**
     * Яку камеру відкривати. Якщо камеру вже відомо (збережена з минулого запуску) — беремо її одразу,
     * не перебираючи характеристики всіх камер пристрою (кожна — окремий виклик до системи).
     */
    private fun resolveTargetId(wantCameraId: String?): String? {
        if (wantCameraId != null) {
            try {
                val cached = specs[wantCameraId]
                if (cached != null) {
                    if (cached.resolutions.isNotEmpty()) return wantCameraId
                } else if (wantCameraId in manager.cameraIdList) {
                    // Характеристики ще не читали: не витрачаємо на них час перед відкриттям — openCamera() прочитає їх
                    // паралельно з тим, як система відкриває камеру.
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
        raiseStatus(context.getString(R.string.status_connection_lost))
        scheduleRetry()
    }

    // =====================================================================
    // Відкриття камери та сесії
    // =====================================================================

    @SuppressLint("MissingPermission") // дозвіл перевіряється в reconcile()
    private fun openCamera(id: String) {
        opening = true
        val gen = ++generation
        try {
            // Спершу просимо систему відкрити камеру, і лише потім читаємо характеристики: поки сервіс камери
            // працює над відкриттям, цей потік не простоює. Колбек onOpened не спрацює, доки ми не повернемось.
            manager.openCamera(id, DeviceCb(gen), handler)
            StartupTrace.mark { "openCamera() викликано" }
            val loaded = loadSpec(id)
            spec = loaded
            if (loaded.resolutions.isEmpty()) {
                // Камера без придатних розмірів (напр. зникла зовнішня): забуваємо її й обираємо іншу.
                closeCamera()
                wantCameraId = null
                post(::reconcile)
            }
        } catch (ex: Exception) {
            opening = false
            handleLost("openCamera: ${ex.message}")
        }
    }

    private fun onCameraOpened(gen: Int, camera: CameraDevice) {
        if (gen != generation) {
            camera.close()
            return
        }

        StartupTrace.mark { "Камеру відкрито (CameraDevice.onOpened)" }
        device = camera
        opening = false
        retryDelayMs = 1000L
        handler.removeCallbacks(retryRunnable)
        Prefs(context).cameraId = camera.id

        // Спершу запускаємо сесію (якщо поверхня вже є). Повний перелік камер для інтерфейсу збираємо лише ПІСЛЯ того,
        // як сесія налаштована й перший запит відправлено: колбеки сесії приходять на цей самий потік, тож збирання
        // (≈100 мс на Honor 10) раніше затримало б появу першого кадра. Запасний таймер — на випадок, якщо сесія не стартує.
        capabilitiesPending = true
        reconcile()
        handler.postDelayed(publishPendingRunnable, CAPABILITIES_FALLBACK_MS)
    }

    private fun onCameraLost(gen: Int, camera: CameraDevice, reason: String) {
        try {
            camera.close()
        } catch (_: Exception) {
            // вже закрита
        }

        if (gen != generation) return

        device = null
        handleLost(reason)
    }

    @Suppress("DEPRECATION") // SessionConfiguration — лише з API 28, а minSdk = 24
    private fun createSession(resolution: CameraResolution, highSpeed: HighSpeedConfig?) {
        val device = device!!
        val texture = texture!!
        try {
            sessionPending = true
            session = null // стара сесія закриється автоматично при створенні нової
            resetFrameRate()

            texture.setDefaultBufferSize(resolution.width, resolution.height)
            val newSurface = Surface(texture)
            surface = newSurface

            val gen = generation
            if (highSpeed != null) {
                // Спеціальна сесія для частоти понад 30 кадрів/с: лише поверхня прев'ю заявленого розміру.
                device.createConstrainedHighSpeedCaptureSession(listOf(newSurface), SessionCb(gen, resolution, true, false), handler)
                return
            }

            // Крім прев'ю, у сесії є JPEG-потік для знімків (камера віддає з нього повнорозмірне фото).
            val still = openStillReader(resolution)
            device.createCaptureSession(listOfNotNull(newSurface, still?.surface), SessionCb(gen, resolution, false, still != null), handler)
        } catch (ex: Exception) {
            sessionPending = false
            handleLost("createCaptureSession: ${ex.message}")
        }
    }

    private fun onSessionConfigured(gen: Int, session: CameraCaptureSession, resolution: CameraResolution, highSpeed: Boolean) {
        if (gen != generation) {
            try {
                session.close()
            } catch (_: Exception) {
                // ігноруємо
            }
            pendingReader?.close()
            pendingReader = null
            return
        }

        StartupTrace.mark { "Сесію налаштовано (${resolution.key}${if (highSpeed) ", високошвидкісна" else ""})" }
        // Потік попередньої сесії більше не потрібен; новий (якщо є) стає активним — до raiseGeometry().
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

        // У високошвидкісному режимі розмір кадру не обирається користувачем: інтерфейс не повинен
        // «запам'ятати» його як вибраний (інакше він затре справжній вибір після повернення в звичайний режим).
        if (!highSpeed) emit { it.onResolutionApplied(resolution) }

        raiseStatus(null)

        applyRepeating()

        // У режимі AUTO камера сама не фокусується — один раз запускаємо автофокус вручну.
        if (spec.afMode == CameraMetadata.CONTROL_AF_MODE_AUTO && !highSpeed) {
            triggerAf(CameraMetadata.CONTROL_AF_TRIGGER_START)
        }

        // Режим змінився — максимум зуму та підтримка режиму можуть відрізнятись; а після відкриття камери
        // тут же публікуємо відкладені можливості (запит уже відправлено, тож це не гальмує перший кадр).
        if (highSpeed != publishedHighSpeed) publishCapabilities() else publishPendingCapabilities()

        reconcile()
    }

    /** Передає інтерфейсу актуальні можливості поточної камери. */
    private fun publishCapabilities() {
        capabilitiesPending = false
        val spec = spec ?: return

        publishedHighSpeed = activeHighSpeed
        val capabilities = buildCapabilities(spec.id)
        lastCapabilities = capabilities
        emit { it.onCapabilities(capabilities) }
        StartupTrace.mark { "Можливості камери передано інтерфейсу" }
    }

    /** Відкладена публікація можливостей після відкриття камери (див. onCameraOpened). */
    private fun publishPendingCapabilities() {
        handler.removeCallbacks(publishPendingRunnable)
        if (capabilitiesPending && device != null) publishCapabilities()
    }

    private fun currentGeometry(): PreviewGeometry? {
        val spec = spec ?: return null
        val resolution = activeResolution ?: return null

        // Фокус по дотику у високошвидкісній сесії неможливий (вона не приймає одиночних запитів і тригерів).
        // Так само й яскравість: високошвидкісний запит навмисно мінімальний, експокомпенсації в ньому немає.
        return PreviewGeometry(
            resolution.width, resolution.height, spec.sensorOrientation, spec.isFront,
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

    private fun onSessionConfigureFailed(gen: Int, resolution: CameraResolution, highSpeed: Boolean, withStill: Boolean) {
        pendingReader?.close()
        pendingReader = null
        if (gen != generation) return

        sessionPending = false
        publishPendingCapabilities()

        if (withStill) {
            // Можливо, відхилено саме поєднання прев'ю з JPEG-потоком. Не відкидаємо через це роздільність:
            // пробуємо ту саму без знімків (тоді знімок для неї недоступний).
            blockedStill.add(spec!!.id to resolution)
            Log.w(TAG, "Configure with JPEG failed for ${resolution.key}, retrying without")
            reconcile()
            return
        }

        if (highSpeed) {
            // Не чіпаємо звичайний список розмірів: відхилено саме високошвидкісний режим цього розміру.
            val failedSpec = spec!!
            blockedHighSpeed.add(failedSpec.id to resolution)
            Log.w(TAG, "High-speed configure failed for ${resolution.key}")

            if (resolveHighSpeed(failedSpec) == null) {
                failedSpec.highSpeedBroken = true
                raiseStatus(context.getString(R.string.status_highspeed_unsupported))
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
                context.getString(
                    R.string.status_resolution_unsupported, resolution.width, resolution.height, next.width, next.height,
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
    // Запити до камери: зум, режим автофокусу, регіони
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

        // Torch тримає спалах увімкненим на кожному кадрі (в тому числі під час фокусування).
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (wantFlash && spec.hasFlash) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
        )

        // Без явного діапазону драйвер Honor 10 за замовчуванням тримає прев'ю на ~25 к/с (діапазон [14,25]), хоча для
        // більшості розмірів заявляє 30; з явним [14,30] при світлі виходить 30. Верхня межа — заявлена для цього розміру.
        aeFpsRangeFor(spec, activeResolution)?.let { (lower, upper) ->
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(lower, upper))
        }

        val crop = cropForZoom(spec)
        builder.set(CaptureRequest.SCALER_CROP_REGION, crop)

        // Регіон поза видимою зоною камера ігнорує, тому приймаємо його лише коли він у кадрі.
        val af = afRegions
        if (af != null && spec.maxAfRegions > 0 && isVisible(af, crop)) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, af)
        }
        val ae = aeRegions
        if (ae != null && spec.maxAeRegions > 0 && isVisible(ae, crop)) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, ae)
        }

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
     * Запит у високошвидкісній сесії. Навмисно мінімальний: лише авто-режими, діапазон частоти з переліку камери та зум.
     * Спалах, регіони фокусу й тригери сюди не додаємо — така сесія приймає не все, а драйвер Honor на
     * неприпустимих параметрах уже зависав.
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
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropForZoom(spec))

            val requests = highSpeedSession.createHighSpeedRequestList(builder.build())
            StartupTrace.req { "HS burst: crop=${cropForZoom(spec)} requests=${requests.size}" }
            highSpeedSession.setRepeatingBurst(requests, captureCallback, handler)
            StartupTrace.req { "HS burst: setRepeatingBurst returned" }
        } catch (ex: Exception) {
            Log.w(TAG, "High-speed burst: ${ex.message}")
            StartupTrace.req { "HS burst EXCEPTION: ${ex.javaClass.simpleName}: ${ex.message}" }
        }
    }

    /** Максимум зуму, який зараз пропонується: у високошвидкісному режимі — лише заявлений камерою. */
    private fun effectiveMaxZoom(spec: CameraSpec) = if (highSpeedWanted(spec)) spec.declaredMaxZoom else spec.maxZoom

    private fun currentZoom(spec: CameraSpec) = wantZoom.coerceIn(1f, maxOf(1f, effectiveMaxZoom(spec)))

    /** Область сенсора, яку треба показувати при поточному зумі (по центру). */
    private fun cropForZoom(spec: CameraSpec): Rect {
        val zoom = currentZoom(spec)
        val active = spec.activeArray
        val width = active.width()
        val height = active.height()
        val cropW = (width / zoom).toInt() and 1.inv()
        val cropH = (height / zoom).toInt() and 1.inv()
        val left = active.left + (width - cropW) / 2
        val top = active.top + (height - cropH) / 2
        return Rect(left, top, left + cropW, top + cropH)
    }

    /**
     * Фактично видима частина сенсора: камера додатково обрізає область зуму до пропорцій
     * потоку (напр. 16:9 з сенсора 4:3). Потрібно для точного відображення дотику в координати сенсора.
     */
    private fun visibleSensorRect(spec: CameraSpec, stream: CameraResolution): Rect {
        val crop = cropForZoom(spec)
        val streamAspect = stream.width.toDouble() / stream.height
        val cropAspect = crop.width().toDouble() / crop.height()

        if (Math.abs(streamAspect - cropAspect) < 1e-3) return crop

        if (cropAspect > streamAspect) {
            val w = Math.round(crop.height() * streamAspect).toInt()
            val dx = (crop.width() - w) / 2
            return Rect(crop.left + dx, crop.top, crop.left + dx + w, crop.bottom)
        }

        val h = Math.round(crop.width() / streamAspect).toInt()
        val dy = (crop.height() - h) / 2
        return Rect(crop.left, crop.top + dy, crop.right, crop.top + dy + h)
    }

    private fun isVisible(regions: Array<MeteringRectangle>?, crop: Rect): Boolean {
        val rect = regions?.firstOrNull()?.rect ?: return false
        val cx = rect.centerX()
        val cy = rect.centerY()
        return cx >= crop.left && cx < crop.right && cy >= crop.top && cy < crop.bottom
    }

    // =====================================================================
    // Знімок
    // =====================================================================

    /** Створює приймач JPEG для роздільності прев'ю; null — знімки для неї недоступні. */
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
     * Робить знімок: повнорозмірний JPEG із тими самими зумом, яскравістю, спалахом і фокусом, що й у прев'ю.
     * [displayRotation] — поточний поворот екрана (0/90/180/270), від нього залежить, як повернути фото.
     * Результат — [CameraListener.onPhotoSaved] або [CameraListener.onPhotoFailed].
     */
    fun capturePhoto(displayRotation: Int) = post {
        if (focusPhase != FocusPhase.None) {
            // Фокус по дотику (перший тап подвійного) ще триває: чекаємо на результат, щоб знімок не вийшов розфокусованим.
            pendingCaptureRotation = displayRotation
            handler.removeCallbacks(pendingCaptureRunnable)
            handler.postDelayed(pendingCaptureRunnable, CAPTURE_WAIT_FOR_FOCUS_MS)
        } else {
            doCapturePhoto(displayRotation)
        }
    }

    /** Фокус завершився (або вийшов час очікування) — робимо знімок, що чекав на нього. */
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
        StartupTrace.req { "capturePhoto: session=${session != null} reader=${reader != null} hs=$activeHighSpeed rotation=$displayRotation" }
        if (spec == null || session == null || reader == null || device == null || activeHighSpeed) {
            emit { it.onPhotoFailed() }
            return
        }

        try {
            val builder = device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            // Після фокусу по дотику лінза залочена в режимі AUTO — лишаємо його й для знімка.
            builder.set(CaptureRequest.CONTROL_AF_MODE, if (focusAutoMode) CameraMetadata.CONTROL_AF_MODE_AUTO else spec.afMode)
            if (spec.evMax > spec.evMin) {
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, wantEv.coerceIn(spec.evMin, spec.evMax))
            }
            // Спалах у режимі ліхтарика лишається ввімкненим і для знімка.
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (wantFlash && spec.hasFlash) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
            )
            val crop = cropForZoom(spec)
            builder.set(CaptureRequest.SCALER_CROP_REGION, crop)
            val af = afRegions
            if (af != null && spec.maxAfRegions > 0 && isVisible(af, crop)) builder.set(CaptureRequest.CONTROL_AF_REGIONS, af)
            val ae = aeRegions
            if (ae != null && spec.maxAeRegions > 0 && isVisible(ae, crop)) builder.set(CaptureRequest.CONTROL_AE_REGIONS, ae)

            builder.set(CaptureRequest.JPEG_ORIENTATION, PhotoMath.jpegOrientation(spec.sensorOrientation, displayRotation, spec.isFront))
            builder.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)

            session.capture(builder.build(), stillCallback, handler)
        } catch (ex: Exception) {
            Log.w(TAG, "capturePhoto: ${ex.message}")
            emit { it.onPhotoFailed() }
        }
    }

    /** JPEG готовий: забираємо байти й передаємо запис у галерею окремому потоку, щоб не блокувати камеру. */
    private fun onStillAvailable(source: ImageReader) {
        val image = try {
            source.acquireNextImage()
        } catch (ex: Exception) {
            Log.w(TAG, "acquireNextImage: ${ex.message}")
            null
        } ?: return

        val bytes = try {
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
    // Фокус по дотику
    // =====================================================================

    private fun startFocus(s: Float, t: Float) {
        val spec = spec
        val resolution = activeResolution
        val session = session
        val noAf = spec != null && spec.afMode == CameraMetadata.CONTROL_AF_MODE_OFF
        if (session == null || spec == null || resolution == null || activeHighSpeed ||
            (noAf && spec.maxAeRegions == 0)
        ) {
            emit { it.onFocus(FocusStatus.Failed) } // прибираємо індикатор, який вже показано
            return
        }

        // «Природні» координати (s,t) → координати буфера сенсора (поворот назад на sensorOrientation).
        val sd = s.toDouble()
        val td = t.toDouble()
        val (sx, sy) = when (spec.sensorOrientation) {
            90 -> td to 1 - sd
            180 -> 1 - sd to 1 - td
            270 -> 1 - td to sd
            else -> sd to td
        }

        val visible = visibleSensorRect(spec, resolution)
        val px = visible.left + Math.round(sx * visible.width()).toInt()
        val py = visible.top + Math.round(sy * visible.height()).toInt()
        var side = maxOf(32, (minOf(visible.width(), visible.height()) * 0.18).toInt())

        val active = spec.activeArray
        side = minOf(side, minOf(active.width(), active.height()))
        val left = (px - side / 2).coerceIn(active.left, active.right - side)
        val top = (py - side / 2).coerceIn(active.top, active.bottom - side)

        val region = MeteringRectangle(left, top, side, side, MeteringRectangle.METERING_WEIGHT_MAX)
        afRegions = if (spec.maxAfRegions > 0 && !noAf) arrayOf(region) else null
        aeRegions = if (spec.maxAeRegions > 0) arrayOf(region) else null

        if (noAf) {
            // Камера без автофокусу (зазвичай передня): у цій точці лише вимірюємо експозицію.
            applyRepeating()
            emit { it.onFocus(FocusStatus.Metered) }
            return
        }

        val token = ++focusToken
        focusPhase = FocusPhase.Triggering
        focusAutoMode = spec.hasAfAuto
        emit { it.onFocus(FocusStatus.Searching) }
        StartupTrace.req {
            "TAP s=${"%.2f".format(s)} t=${"%.2f".format(t)} -> sensor ($px,$py) region=[$left,$top ${side}x$side] " +
                "visible=$visible afMode=${spec.afMode} afRegionsSet=${afRegions != null}"
        }

        try {
            // Скидаємо попередній тригер, потім запускаємо новий і оновлюємо регіони в повторюваному запиті.
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
        flushPendingCapture() // лінза залочена (режим AUTO) — знімок, що чекав на фокус, робимо саме зараз

        // Коротко тримаємо блокування, потім повертаємо безперервний автофокус (регіон лишається).
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
            return // високошвидкісна сесія не приймає одиночних запитів і тригерів
        }

        try {
            session.capture(buildRequest(trigger).build(), captureCallback, handler)
        } catch (ex: Exception) {
            Log.w(TAG, "triggerAf($trigger): ${ex.message}")
        }
    }

    private fun onCaptureCompleted(request: CaptureRequest, result: TotalCaptureResult) {
        measureFrameRate(result)
        if (!activeHighSpeed) {
            checkZoomHonored(request, result) // у високошвидкісному режимі область кадрування може не діяти — це не «обрізання» зуму
        }

        traceAf(request, result)
        if (focusPhase == FocusPhase.None) return

        if (focusPhase == FocusPhase.Triggering) {
            // Дочікуємось кадру, у якому справді був відправлений тригер START.
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

    /** Діагностика AF (тег NSC-Req): стан, режим, тригер запиту, регіони у відповіді, дистанція лінзи. */
    private fun traceAf(request: CaptureRequest, result: TotalCaptureResult) = StartupTrace.req {
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
    // Фактична частота кадрів (показується біля іконки спалаху)
    // ---------------------------------------------------------------------

    private var fpsFirstTimestamp = 0L
    private var fpsCount = 0
    private var reportedFps = 0

    /**
     * Рахує кадри за мітками часу сенсора (точніше, ніж за моментом надходження callback-ів) у вікні ≈1 с
     * і повідомляє інтерфейс, коли ціле значення змінилось.
     */
    private fun measureFrameRate(result: TotalCaptureResult) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
        if (timestamp <= 0) return

        if (fpsCount == 0) fpsFirstTimestamp = timestamp

        fpsCount++
        val span = timestamp - fpsFirstTimestamp
        if (span < FPS_WINDOW_NS) return

        val fps = (fpsCount - 1) * 1e9 / span
        fpsCount = 0
        traceFps(fps, result)
        publishFrameRate(fps.roundToInt())
    }

    private fun publishFrameRate(fps: Int) {
        if (fps == reportedFps) return

        reportedFps = fps
        emit { it.onFrameRate(fps) }
    }

    /** Камеру закрито або перенастроєно — старе значення вже недостовірне. */
    private fun resetFrameRate() {
        fpsCount = 0
        publishFrameRate(0)
    }

    private fun traceFps(fps: Double, result: TotalCaptureResult) = StartupTrace.fps {
        val key = activeResolution?.key ?: "?"
        val declared = spec?.frameRates?.get(key) ?: 0.0
        val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val frameNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
        val aeRange = spec?.let { aeFpsRangeFor(it, activeResolution) }
        "$key: measured ${"%.1f".format(fps)} fps, declared ${"%.0f".format(declared)}, " +
            "AE range ${aeRange?.let { "[${it.first},${it.second}]" } ?: "not set"}; " +
            "exposure ${"%.1f".format(exposureNs / 1e6)} ms, frame duration ${"%.1f".format(frameNs / 1e6)} ms"
    }

    // ---------------------------------------------------------------------
    // Зум понад заявлений максимум (лише Huawei/Honor, див. HUAWEI_MAX_ZOOM)
    //
    // Якщо камера мовчки обрізає запитаний зум (результат кадру показує ширшу область, ніж запитано), знижуємо
    // максимум до реально виконуваного, щоб повзунок не обіцяв більше, ніж є. Зависання драйвера так не виправити:
    // у такому разі потік камери блокується всередині виклику драйвера, тому понад перевірені межі не йдемо взагалі.
    // ---------------------------------------------------------------------

    private fun checkZoomHonored(request: CaptureRequest, result: TotalCaptureResult) {
        val spec = spec ?: return
        if (spec.maxZoom <= spec.declaredMaxZoom + 0.01f) return // запитуємо в межах заявленого — перевіряти нічого

        val asked = request.get(CaptureRequest.SCALER_CROP_REGION)
        val applied = result.get(CaptureResult.SCALER_CROP_REGION)
        if (asked == null || asked.width() <= 0 || applied == null || applied.width() <= 0) return

        // Допуск 3%: камери трохи округлюють область. Вужча область — це лише підгонка під пропорції потоку.
        if (applied.width() <= asked.width() * 1.03f) return

        val achieved = spec.activeArray.width() / applied.width().toFloat()
        val limit = maxOf(spec.declaredMaxZoom, floor(achieved * 10f + 0.01f) / 10f)
        if (limit >= spec.maxZoom - 0.05f) return

        Log.w(TAG, "Camera ${spec.id} crops the requested zoom; max ${spec.maxZoom}x -> ${limit}x")
        spec.maxZoom = limit
        if (wantZoom > limit) wantZoom = limit

        // Інтерфейс опускає повзунок до нового максимуму.
        val capabilities = buildCapabilities(spec.id)
        lastCapabilities = capabilities
        emit { it.onCapabilities(capabilities) }
        applyRepeating()
    }

    // =====================================================================
    // Характеристики камер
    // =====================================================================

    private class CameraSpec(
        val id: String,
        val name: String,
        val isFront: Boolean,
        val sensorOrientation: Int,
        val activeArray: Rect,

        /** Максимум цифрового зуму, який камера заявляє через стандартний API. */
        val declaredMaxZoom: Float,

        /** Максимум, який пропонуємо користувачу (≥ заявленого); знижується, якщо камера його не виконує. */
        var maxZoom: Float,

        val afMode: Int,

        /** Камера вміє режим AUTO (потрібен для справжнього фокусування по дотику, див. startFocus). */
        val hasAfAuto: Boolean,
        val maxAfRegions: Int,
        val maxAeRegions: Int,
        val hasFlash: Boolean,

        /** Діапазон індексів експокомпенсації (яскравість) і крок одного індексу в EV. */
        val evMin: Int,
        val evMax: Int,
        val evStep: Float,

        /** Камера придатна для показу зображення (не depth/IR-сенсор тощо). */
        val backwardCompatible: Boolean,
        val resolutions: List<CameraResolution>,

        /** Розміри, у яких камера віддає JPEG (знімки), від найбільшого. */
        val jpegSizes: List<CameraResolution>,
        val frameRates: Map<String, Double>,

        /** Діапазони цільової частоти кадрів, які камера дозволяє для автоекспозиції. */
        val aeFpsRanges: List<Pair<Int, Int>>,

        /** Високошвидкісні режими камери (розмір + діапазон частоти), від найбільшого розміру; порожньо — немає. */
        val highSpeedConfigs: List<HighSpeedConfig>,
        val highSpeedAfMode: Int,
    ) {
        /** Пристрій відхилив усі високошвидкісні режими — більше їх не пропонуємо. */
        var highSpeedBroken = false

        val supportsHighSpeed: Boolean get() = !highSpeedBroken && highSpeedConfigs.isNotEmpty()
    }

    /** Високошвидкісний режим камери: розмір кадру та дозволений діапазон частоти (напр. 1920×1080, [30,120]). */
    private data class HighSpeedConfig(val size: CameraResolution, val lower: Int, val upper: Int)

    private fun isBack(id: String) = !loadSpec(id).isFront

    private fun loadSpec(id: String): CameraSpec {
        specs[id]?.let { return it }

        val ch = manager.getCameraCharacteristics(id)

        val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: -1
        val orientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val maxZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val maxAf = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAe = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
        val capabilities = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)

        val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        val afMode = when {
            afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) -> CameraMetadata.CONTROL_AF_MODE_AUTO
            else -> CameraMetadata.CONTROL_AF_MODE_OFF
        }

        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
        var resolutions = (sizes ?: emptyArray())
            .map { CameraResolution(it.width, it.height) }
            .distinct()
            .sortedByDescending { it.pixels }

        // Зовсім крихітні розміри (QCIF тощо) нікому не потрібні — відкидаємо, якщо є з чого обирати.
        val usable = resolutions.filter { it.width >= 320 && it.height >= 240 }
        if (usable.isNotEmpty()) resolutions = usable

        val aeRanges = readAeFpsRanges(ch)

        val newSpec = CameraSpec(
            id = id,
            name = context.getString(
                when (facing) {
                    CameraMetadata.LENS_FACING_FRONT -> R.string.camera_front
                    CameraMetadata.LENS_FACING_EXTERNAL -> R.string.camera_external
                    else -> R.string.camera_back
                },
            ),
            isFront = facing == CameraMetadata.LENS_FACING_FRONT,
            sensorOrientation = orientation,
            activeArray = active,
            declaredMaxZoom = maxOf(1f, maxZoom),
            maxZoom = maxOf(maxOf(1f, maxZoom), if (isHuaweiFamily && facing == CameraMetadata.LENS_FACING_BACK) HUAWEI_MAX_ZOOM else 0f),
            afMode = afMode,
            hasAfAuto = afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO),
            maxAfRegions = maxAf,
            maxAeRegions = maxAe,
            hasFlash = hasFlash,
            evMin = evRange?.lower ?: 0,
            evMax = evRange?.upper ?: 0,
            evStep = evStep?.toFloat() ?: 0f,
            backwardCompatible = capabilities.isEmpty() ||
                capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE),
            resolutions = resolutions,
            jpegSizes = (map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray())
                .map { CameraResolution(it.width, it.height) }
                .distinct()
                .sortedByDescending { it.pixels },
            frameRates = computeFrameRates(map, sizes, aeRanges.maxOfOrNull { it.second } ?: 0),
            aeFpsRanges = aeRanges,
            highSpeedConfigs = readHighSpeedConfigs(capabilities, map),
            highSpeedAfMode = if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            } else {
                afMode
            },
        )

        StartupTrace.req {
            val comp = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val step = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            val aeModes = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toList()
            "spec $id: maxAfRegions=$maxAf maxAeRegions=$maxAe afModes=${afModes.toList()} aeModes=$aeModes " +
                "aeComp=$comp step=$step hasFlash=$hasFlash activeArray=$active"
        }

        specs[id] = newSpec
        return newSpec
    }

    /** Камери, придатні для показу зображення (без depth/IR-сенсорів тощо). */
    private fun listCameras(): List<CameraDescriptor> {
        val usable = ArrayList<CameraSpec>()
        val ids = try {
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

        // Якщо однакових камер кілька — нумеруємо їх: «Задня камера 1», «Задня камера 2».
        return usable
            .groupBy { it.name }
            .values
            .flatMap { group ->
                if (group.size == 1) {
                    group.map { CameraDescriptor(it.id, it.name) }
                } else {
                    group.mapIndexed { i, s -> CameraDescriptor(s.id, context.getString(R.string.camera_numbered, s.name, i + 1)) }
                }
            }
            .sortedWith(compareBy({ if (isBack(it.id)) 0 else 1 }, { it.id }))
    }

    private fun buildCapabilities(id: String): CameraCapabilities {
        val spec = loadSpec(id)
        val cameras = listCameras()
        val highSpeed = if (spec.supportsHighSpeed) resolveHighSpeed(spec) else null
        return CameraCapabilities(
            camera = cameras.firstOrNull { it.id == id } ?: CameraDescriptor(id, spec.name),
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
     * Обирає роздільність: запитану (якщо камера її підтримує і вона не відхилена),
     * інакше збалансовану — найбільшу до ~2 МП із повним полем зору сенсора.
     */
    private fun resolveResolution(): CameraResolution? {
        val spec = spec ?: return null

        val candidates = spec.resolutions.filter { (spec.id to it) !in blocked }
        if (candidates.isEmpty()) return null

        val wanted = wantResolution
        if (wanted != null && wanted in candidates) return wanted

        val sensorAspect = spec.activeArray.width().toDouble() / spec.activeArray.height()
        val balanced = candidates.filter { it.pixels <= DEFAULT_PREVIEW_PIXELS }
        if (balanced.isEmpty()) return candidates.last() // навіть найменший завеликий — беремо найменший

        val fullFov = balanced.filter { Math.abs(it.width.toDouble() / it.height - sensorAspect) < 0.02 }
        return (fullFov.ifEmpty { balanced }).maxByOrNull { it.pixels }
    }

    /**
     * Найбільша частота кадрів для кожної роздільності за даними камери: 1 / мінімальна тривалість кадру цього розміру.
     * Обмежена верхньою межею діапазонів автоекспозиції — вище в прев'ю камера все одно не піде.
     */
    private fun computeFrameRates(map: StreamConfigurationMap?, sizes: Array<Size>?, maxAeFps: Int): Map<String, Double> {
        val rates = HashMap<String, Double>()
        if (map == null || sizes == null) return rates

        for (size in sizes) {
            try {
                val frameNs = map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
                if (frameNs <= 0) continue

                var fps = 1e9 / frameNs
                if (maxAeFps > 0) fps = minOf(fps, maxAeFps.toDouble())

                if (fps >= 1) rates[CameraResolution(size.width, size.height).key] = Math.rint(fps)
            } catch (ex: Exception) {
                Log.w(TAG, "Frame duration ${size.width}x${size.height}: ${ex.message}")
            }
        }

        return rates
    }

    /**
     * Високошвидкісні режими камери: розміри з частотою понад 30 кадрів/с (напр. 1920×1080 і 1280×720 до 120).
     * Для кожного береться діапазон з найвищою верхньою межею й найнижчою нижньою ([30,120] — у темряві
     * камера може знизити частоту, а не лишитись без експозиції). Порожньо, якщо камера цього режиму не заявляє.
     */
    private fun readHighSpeedConfigs(capabilities: IntArray, map: StreamConfigurationMap?): List<HighSpeedConfig> {
        val configs = ArrayList<HighSpeedConfig>()
        try {
            if (map == null || !capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
                return configs
            }

            for (size in map.highSpeedVideoSizes ?: emptyArray()) {
                var lower = 0
                var upper = 0
                for (range in map.getHighSpeedVideoFpsRangesFor(size) ?: emptyArray()) {
                    val l = range.lower
                    val u = range.upper
                    if (u > upper || (u == upper && l < lower)) {
                        lower = l
                        upper = u
                    }
                }

                if (upper >= 60) configs.add(HighSpeedConfig(CameraResolution(size.width, size.height), lower, upper))
            }
        } catch (ex: Exception) {
            Log.w(TAG, "High-speed configs: ${ex.message}")
            configs.clear()
        }

        return configs.sortedByDescending { it.size.pixels }
    }

    /** Діапазони цільової частоти кадрів, дозволені камерою для автоекспозиції (порожньо — невідомо). */
    private fun readAeFpsRanges(ch: CameraCharacteristics): List<Pair<Int, Int>> = try {
        ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower to it.upper }
            ?: emptyList()
    } catch (ex: Exception) {
        Log.w(TAG, "AE fps ranges: ${ex.message}")
        emptyList()
    }

    /**
     * Діапазон цільової частоти кадрів для вибраної роздільності: найвища верхня межа, не більша за заявлену для цього
     * розміру, і найнижча нижня межа серед таких (у темряві камера, як і раніше, може подовжувати витримку).
     */
    private fun aeFpsRangeFor(spec: CameraSpec, resolution: CameraResolution?): Pair<Int, Int>? {
        if (resolution == null || spec.aeFpsRanges.isEmpty()) return null
        val cap = spec.frameRates[resolution.key] ?: return null

        val candidates = spec.aeFpsRanges.filter { it.second <= cap.toInt() }
        if (candidates.isEmpty()) return null

        val upper = candidates.maxOf { it.second }
        return candidates.filter { it.second == upper }.minByOrNull { it.first }
    }

    private fun raiseStatus(status: String?) = emit { it.onStatus(status) }

    private inline fun emit(block: (CameraListener) -> Unit) {
        for (listener in listeners) block(listener)
    }

    // =====================================================================
    // Колбеки Camera2
    // =====================================================================

    private inner class DeviceCb(private val generation: Int) : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) = onCameraOpened(generation, camera)

        override fun onDisconnected(camera: CameraDevice) = onCameraLost(generation, camera, "camera disconnected")

        override fun onError(camera: CameraDevice, error: Int) = onCameraLost(generation, camera, "camera error $error")
    }

    private inner class SessionCb(
        private val generation: Int,
        private val resolution: CameraResolution,
        private val highSpeed: Boolean,
        private val withStill: Boolean,
    ) : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) =
            onSessionConfigured(generation, session, resolution, highSpeed)

        override fun onConfigureFailed(session: CameraCaptureSession) =
            onSessionConfigureFailed(generation, resolution, highSpeed, withStill)
    }

    /** Результат знімка: успіх приходить через ImageReader, а тут лише помилка. */
    private inner class StillCb : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
            Log.w(TAG, "Still capture failed: reason ${failure.reason}")
            emit { it.onPhotoFailed() }
        }
    }

    private inner class CaptureCb : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) =
            this@CameraController.onCaptureCompleted(request, result)
    }

    companion object {
        private const val FOCUS_HOLD_MS = 1200L      // скільки тримати блокування фокусу після дотику
        private const val FOCUS_TIMEOUT_MS = 4000L   // максимум очікування результату фокусування
        private const val MAX_RETRY_DELAY_MS = 8000L
        private const val DEFAULT_PREVIEW_PIXELS = 1920L * 1080L
        private const val FPS_WINDOW_NS = 1_000_000_000L
        private const val CAPABILITIES_FALLBACK_MS = 1500L
        private const val JPEG_QUALITY: Byte = 95
        private const val CAPTURE_WAIT_FOR_FOCUS_MS = 1500L // скільки знімок чекає на фокус по дотику

        /**
         * Максимум зуму, який пропонуємо на задній камері Huawei/Honor. Стандартний Camera2 там заявляє лише 6x, а власний
         * додаток камери — 10x (у характеристиках камери: opticalMaxZoomValue = 10). Перевірено на Honor 10: драйвер виконує
         * область кадрування до 10x і далі (аж до ~14x), але на ~16x ЗАВИСАЄ (потік камери блокується на десятки секунд),
         * тому більше 10x не пропонуємо. Для інших виробників залишаємо заявлене значення: як їхні драйвери поводяться
         * понад нього, невідомо, а зависання камери гірше за відсутність зуму.
         */
        private const val HUAWEI_MAX_ZOOM = 10f

        /** Якщо інтерфейс так і не підключився (процес запущено не для показу екрана) — камеру не тримаємо. */
        private const val WARM_UP_TIMEOUT_MS = 15_000L

        private val isHuaweiFamily: Boolean
            get() = Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) ||
                Build.BRAND.equals("HONOR", ignoreCase = true) ||
                Build.MANUFACTURER.equals("HONOR", ignoreCase = true)

        // Спільний екземпляр на весь процес: камеру можна прогріти ще до створення інтерфейсу.
        private val sharedLock = Any()
        // Контролер тримає лише ApplicationContext (див. getShared), тож витоку активності тут немає.
        @SuppressLint("StaticFieldLeak")
        private var shared: CameraController? = null

        /** Єдиний контролер процесу. Інтерфейс підключається до нього, а не створює власний. */
        fun getShared(context: Context): CameraController = synchronized(sharedLock) {
            shared ?: CameraController(context.applicationContext ?: context).also { shared = it }
        }

        /**
         * Починає відкривати камеру одразу при старті процесу — паралельно зі створенням інтерфейсу
         * (це сотні мілісекунд, які раніше камера просто чекала). Нічого не робить без дозволу на камеру.
         * Уся робота (читання налаштувань, характеристики камери) — на потоці камери, не на головному.
         */
        fun warmUp(context: Context) {
            try {
                if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

                val controller = getShared(context)
                controller.post {
                    val prefs = Prefs(context)
                    val cameraId = prefs.cameraId
                    controller.wantCameraId = cameraId
                    // Збережену роздільність підхоплюємо вже тут: інакше перша сесія стартувала б зі стандартною
                    // і одразу перебудовувалась би, щойно інтерфейс повідомить обрану.
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
