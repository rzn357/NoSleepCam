package com.exciteddev.nosleepcam.ui

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
import com.exciteddev.nosleepcam.R
import com.exciteddev.nosleepcam.camera.CameraCapabilities

/**
 * Controls over the camera: zoom slider, flash, gear, status and permission request.
 * Contains no camera logic — only display and events (MainActivity holds the logic).
 */
internal class CameraOverlay(
    context: Context,
) : FrameLayout(context) {
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

    /** The user changed the zoom with the slider. */
    var onZoomChanged: ((Double) -> Unit)? = null
    var onFlashClicked: (() -> Unit)? = null
    var onSettingsClicked: (() -> Unit)? = null
    var onGrantClicked: (() -> Unit)? = null
    var onOpenSystemSettingsClicked: (() -> Unit)? = null

    /** The circle with the last photo was tapped. */
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

        // Shadow around the big number so that it is readable over any image; padding so that the shadow is not clipped.
        zoomBigLabel.setShadowLayer(8f * dp, 0f, 0f, Color.argb(140, 0, 0, 0))
        val pad = (12 * dp).toInt()
        zoomBigLabel.setPadding(pad, pad, pad, pad)

        zoomSlider.onValueChanged = { zoom ->
            setBigZoomNumber(ZoomSlider.formatBigNumber(zoom))
            onZoomChanged?.invoke(zoom)
        }
        zoomSlider.onInteractionStarted = { showBigZoom(true) }
        zoomSlider.onInteractionEnded = { showBigZoom(false) }
        // As in the Huawei camera: while the zoom scale is expanded, the other buttons are hidden.
        zoomSlider.onExpandedChanged = { expanded -> setTopControlsHidden(expanded) }

        flashButton.setOnClickListener { onFlashClicked?.invoke() }
        thumbnail.setOnClickListener { onThumbnailClicked?.invoke() }
        findViewById<ImageButton>(R.id.settings_button).setOnClickListener { onSettingsClicked?.invoke() }
        findViewById<Button>(R.id.grant_button).setOnClickListener { onGrantClicked?.invoke() }
        findViewById<Button>(R.id.open_settings_button).setOnClickListener { onOpenSystemSettingsClicked?.invoke() }

        setBigZoomNumber(ZoomSlider.formatBigNumber(zoomSlider.value))
    }

    /** Applies the camera's capabilities: zoom range and flash availability. */
    fun setCapabilities(
        caps: CameraCapabilities,
        zoom: Double,
    ) {
        zoomSlider.minimumZoom = caps.minZoom
        zoomSlider.maximumZoom = caps.maxZoom
        zoomSlider.value = zoom
        zoomSlider.visibility = if (caps.maxZoom > 1.05) VISIBLE else GONE

        // The flash icon is always visible. If the current camera has no flash (usually the front one) —
        // it is dimmed, and tapping it explains why (see MainActivity.onFlashClicked).
        supportsFlash = caps.supportsFlash
        updateFlashAvailability()
    }

    /** Flash is unavailable in high-speed mode: the icon is dimmed, same as without a hardware flash. */
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

    /** Actual frame rate next to the flash icon; 0 — hide the badge. */
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
     * The expanded zoom scale is ≈33% of the screen height (240 dp on the Honor 10) and is placed symmetrically around the frame centre, while
     * at rest the ring sits in a slot at the centre — all measured on the Huawei camera. In landscape the scale is never shorter
     * than 140 dp. [pageHeight] is the height of the content area in dp.
     */
    fun layoutFor(pageHeight: Double) {
        if (pageHeight <= 0) return

        val inset = ZoomSlider.TRACK_INSET.toDouble()

        // The slider sits near the right edge, below the icon row (frame rate, flash, gear) — they must not
        // overlap. In portrait there is plenty of room; in landscape the slider moves below the icons and, if needed, gets shorter.
        val track =
            minOf(
                minOf(maxOf(pageHeight * TRACK_FRACTION, 140.0), maxOf(140.0, pageHeight * 0.7)),
                maxOf(MIN_TRACK, pageHeight - BUTTONS_BOTTOM - 2 * inset),
            )
        val viewHeight = track + 2 * inset
        val top =
            (pageHeight / 2 - viewHeight / 2)
                .coerceIn(BUTTONS_BOTTOM, maxOf(BUTTONS_BOTTOM, pageHeight - viewHeight))

        val params = zoomSlider.layoutParams as LayoutParams
        params.height = (viewHeight * dp).toInt()
        params.topMargin = (top * dp).toInt()
        zoomSlider.layoutParams = params
    }

    private companion object {
        /** Bottom of the icon row in the top corner (dp): margin 10 + button 44 + spare 4 — see layout/camera_overlay.xml. */
        const val BUTTONS_BOTTOM = 58.0

        /** Shortest zoom scale when there is very little room. */
        const val MIN_TRACK = 100.0

        /** Scale length relative to the content height: 240 dp out of 733 dp on the Honor 10. */
        const val TRACK_FRACTION = 0.327
    }

    /** Photo in the thumbnail circle; null — the circle is hidden. [pop] — a "bounce" when a new photo flies into it. */
    fun setThumbnail(
        image: Bitmap?,
        pop: Boolean = false,
    ) {
        thumbnail.image = image
        if (pop && image != null) thumbnail.pop()
    }

    val thumbnailImage: Bitmap? get() = thumbnail.image

    /** Bounds of the circle in this overlay's coordinate system; null — not laid out yet. */
    fun thumbnailBounds(): RectF? =
        if (width == 0 || thumbnail.width == 0) {
            null
        } else {
            RectF(
                thumbnail.left.toFloat(),
                thumbnail.top.toFloat(),
                thumbnail.right.toFloat(),
                thumbnail.bottom.toFloat(),
            )
        }

    /** The circle is needed right now (it is hidden until the first photo, and the animation flies to its future place). */
    fun showThumbnailPlaceholder() {
        if (thumbnail.visibility != VISIBLE) thumbnail.visibility = INVISIBLE
    }

    private fun setTopControlsHidden(hidden: Boolean) {
        topControls.animate().cancel()
        if (hidden) {
            topControls
                .animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction { topControls.visibility = INVISIBLE }
                .start()
        } else {
            topControls.visibility = VISIBLE
            topControls
                .animate()
                .alpha(1f)
                .setDuration(200)
                .start()
        }
    }

    /** The big zoom number: "76" with a small "x" next to it. */
    private fun setBigZoomNumber(number: String) {
        val text = SpannableStringBuilder(number)
        text.setSpan(AbsoluteSizeSpan(76, true), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = text.length
        text.append("x")
        text.setSpan(AbsoluteSizeSpan(44, true), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        zoomBigLabel.text = text
    }

    /** The big zoom value in the middle of the screen: shown while dragging, fades out afterwards. */
    private fun showBigZoom(show: Boolean) {
        val token = ++bigLabelToken
        if (show) {
            setBigZoomNumber(ZoomSlider.formatBigNumber(zoomSlider.value))
            zoomBigLabel
                .animate()
                .alpha(1f)
                .setDuration(100)
                .start()
            return
        }

        zoomBigLabel.postDelayed({
            if (token == bigLabelToken) {
                zoomBigLabel
                    .animate()
                    .alpha(0f)
                    .setDuration(350)
                    .start()
            }
        }, 700)
    }
}
