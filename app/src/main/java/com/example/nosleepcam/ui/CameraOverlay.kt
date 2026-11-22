package com.example.nosleepcam.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import com.example.nosleepcam.R
import com.example.nosleepcam.camera.CameraCapabilities

/**
 * Елементи керування поверх камери: повзунок зуму, спалах, шестерня, статус і запит дозволу.
 * Не містить логіки камери — лише відображення й події (її тримає MainActivity).
 */
class CameraOverlay(context: Context) : FrameLayout(context) {

    private val dp = resources.displayMetrics.density

    private val zoomSlider: ZoomSlider
    private val zoomBigLabel: TextView
    private val frameRateBadge: TextView
    private val topControls: View
    private val thumbnail: ThumbnailView
    private val flashButton: ImageButton
    private val statusBadge: TextView
    private val permissionPanel: View

    private var bigLabelToken = 0
    private var supportsFlash = true
    private var highSpeed = false

    /** Користувач змінив зум повзунком. */
    var onZoomChanged: ((Double) -> Unit)? = null
    var onFlashClicked: (() -> Unit)? = null
    var onSettingsClicked: (() -> Unit)? = null
    var onGrantClicked: (() -> Unit)? = null
    var onOpenSystemSettingsClicked: (() -> Unit)? = null

    /** Натиснули коло з останнім знімком. */
    var onThumbnailClicked: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.camera_overlay, this, true)

        zoomSlider = findViewById(R.id.zoom_slider)
        zoomBigLabel = findViewById(R.id.zoom_big_label)
        frameRateBadge = findViewById(R.id.frame_rate_badge)
        topControls = findViewById(R.id.top_controls)
        thumbnail = findViewById(R.id.thumbnail)
        flashButton = findViewById(R.id.flash_button)
        statusBadge = findViewById(R.id.status_badge)
        permissionPanel = findViewById(R.id.permission_panel)

        // Тінь навколо великого числа, щоб воно читалось на будь-якому зображенні; поля — щоб тінь не обрізалась.
        zoomBigLabel.setShadowLayer(8f * dp, 0f, 0f, Color.argb(140, 0, 0, 0))
        val pad = (12 * dp).toInt()
        zoomBigLabel.setPadding(pad, pad, pad, pad)

        zoomSlider.onValueChanged = { zoom ->
            setBigZoomNumber(ZoomSlider.formatBigNumber(zoom))
            onZoomChanged?.invoke(zoom)
        }
        zoomSlider.onInteractionStarted = { showBigZoom(true) }
        zoomSlider.onInteractionEnded = { showBigZoom(false) }
        // Як у камері Huawei: поки розгорнута шкала зуму, решта кнопок ховається.
        zoomSlider.onExpandedChanged = { expanded -> setTopControlsHidden(expanded) }

        flashButton.setOnClickListener { onFlashClicked?.invoke() }
        thumbnail.setOnClickListener { onThumbnailClicked?.invoke() }
        findViewById<ImageButton>(R.id.settings_button).setOnClickListener { onSettingsClicked?.invoke() }
        findViewById<Button>(R.id.grant_button).setOnClickListener { onGrantClicked?.invoke() }
        findViewById<Button>(R.id.open_settings_button).setOnClickListener { onOpenSystemSettingsClicked?.invoke() }

        setBigZoomNumber(ZoomSlider.formatBigNumber(zoomSlider.value))
    }

    /** Застосовує можливості камери: діапазон зуму та наявність спалаху. */
    fun setCapabilities(caps: CameraCapabilities, zoom: Double) {
        zoomSlider.minimumZoom = caps.minZoom
        zoomSlider.maximumZoom = caps.maxZoom
        zoomSlider.value = zoom
        zoomSlider.visibility = if (caps.maxZoom > 1.05) VISIBLE else GONE

        // Іконка спалаху видима завжди. Якщо в поточної камери спалаху немає (зазвичай передня) —
        // вона приглушена, а натискання пояснює причину (див. MainActivity.onFlashClicked).
        supportsFlash = caps.supportsFlash
        updateFlashAvailability()
    }

    /** У високошвидкісному режимі спалах недоступний: іконка приглушується, як і без апаратного спалаху. */
    fun setHighSpeed(on: Boolean) {
        highSpeed = on
        updateFlashAvailability()
    }

    private fun updateFlashAvailability() {
        flashButton.alpha = if (supportsFlash && !highSpeed) 1f else 0.4f
    }

    fun setFlash(on: Boolean) {
        flashButton.setImageResource(if (on) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        flashButton.contentDescription =
            context.getString(if (on) R.string.flash_on_description else R.string.flash_off_description)
    }

    /** Фактична частота кадрів біля іконки спалаху; 0 — позначку ховаємо. */
    fun setFrameRate(fps: Int) {
        frameRateBadge.visibility = if (fps > 0) VISIBLE else GONE
        if (fps > 0) frameRateBadge.text = context.getString(R.string.fps_format, fps)
    }

    fun setStatus(status: String?) {
        statusBadge.visibility = if (status.isNullOrEmpty()) GONE else VISIBLE
        statusBadge.text = status
    }

    fun setPermissionPanelVisible(visible: Boolean) {
        permissionPanel.visibility = if (visible) VISIBLE else GONE
    }

    /**
     * Розгорнута шкала зуму — ≈33% висоти екрана (240 dp на Honor 10) і розташована симетрично навколо центру кадра, а в
     * спокої кільце стоїть у слоті по центру, — усе виміряно на камері Huawei. У ландшафті шкала не стає коротшою
     * за 140 dp. [pageHeight] — висота області вмісту в dp.
     */
    fun layoutFor(pageHeight: Double) {
        if (pageHeight <= 0) return

        val inset = ZoomSlider.TRACK_INSET.toDouble()

        // Повзунок стоїть біля правого краю, під рядком іконок (частота кадрів, спалах, шестерня) — вони не повинні
        // накладатись. У портреті місця вдосталь; у ландшафті повзунок опускається під іконки й, якщо треба, коротшає.
        val track = minOf(
            minOf(maxOf(pageHeight * TRACK_FRACTION, 140.0), maxOf(140.0, pageHeight * 0.7)),
            maxOf(MIN_TRACK, pageHeight - BUTTONS_BOTTOM - 2 * inset),
        )
        val viewHeight = track + 2 * inset
        val top = (pageHeight / 2 - viewHeight / 2)
            .coerceIn(BUTTONS_BOTTOM, maxOf(BUTTONS_BOTTOM, pageHeight - viewHeight))

        val params = zoomSlider.layoutParams as LayoutParams
        params.height = (viewHeight * dp).toInt()
        params.topMargin = (top * dp).toInt()
        zoomSlider.layoutParams = params
    }

    private companion object {
        /** Низ рядка іконок у верхньому куті (dp): відступ 10 + кнопка 44 + запас 4 — див. layout/camera_overlay.xml. */
        const val BUTTONS_BOTTOM = 58.0

        /** Найкоротша шкала зуму, коли місця зовсім мало. */
        const val MIN_TRACK = 100.0

        /** Довжина шкали відносно висоти вмісту: 240 dp із 733 dp на Honor 10. */
        const val TRACK_FRACTION = 0.327
    }

    /** Фото в колі-мініатюрі; null — коло ховається. [pop] — «підстрибування», коли в нього прилітає новий знімок. */
    fun setThumbnail(image: Bitmap?, pop: Boolean = false) {
        thumbnail.image = image
        if (pop && image != null) thumbnail.pop()
    }

    val thumbnailImage: Bitmap? get() = thumbnail.image

    /** Межі кола в системі координат цього overlay; null — ще не розкладено. */
    fun thumbnailBounds(): RectF? =
        if (width == 0 || thumbnail.width == 0) null else RectF(
            thumbnail.left.toFloat(), thumbnail.top.toFloat(), thumbnail.right.toFloat(), thumbnail.bottom.toFloat(),
        )

    /** Коло потрібне вже зараз (до першого знімка воно приховане, а анімація летить у його майбутнє місце). */
    fun showThumbnailPlaceholder() {
        if (thumbnail.visibility != VISIBLE) thumbnail.visibility = INVISIBLE
    }

    private fun setTopControlsHidden(hidden: Boolean) {
        topControls.animate().cancel()
        if (hidden) {
            topControls.animate().alpha(0f).setDuration(150).withEndAction { topControls.visibility = INVISIBLE }.start()
        } else {
            topControls.visibility = VISIBLE
            topControls.animate().alpha(1f).setDuration(200).start()
        }
    }

    /** Велике число зуму: «76» і маленьке «x» поруч. */
    private fun setBigZoomNumber(number: String) {
        val text = SpannableStringBuilder(number)
        text.setSpan(AbsoluteSizeSpan(76, true), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = text.length
        text.append("x")
        text.setSpan(AbsoluteSizeSpan(44, true), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        zoomBigLabel.text = text
    }

    /** Велике значення зуму посеред екрана: показуємо під час перетягування, згодом згасає. */
    private fun showBigZoom(show: Boolean) {
        val token = ++bigLabelToken
        if (show) {
            setBigZoomNumber(ZoomSlider.formatBigNumber(zoomSlider.value))
            zoomBigLabel.animate().alpha(1f).setDuration(100).start()
            return
        }

        zoomBigLabel.postDelayed({
            if (token == bigLabelToken) zoomBigLabel.animate().alpha(0f).setDuration(350).start()
        }, 700)
    }
}
