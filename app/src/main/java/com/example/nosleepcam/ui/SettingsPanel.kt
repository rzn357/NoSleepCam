package com.example.nosleepcam.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import com.example.nosleepcam.R
import com.example.nosleepcam.camera.CameraDescriptor
import com.example.nosleepcam.camera.CameraResolution

/** Рядок списку роздільностей: розмір кадру й найбільша частота кадрів для нього. */
private class ResolutionItem(val resolution: CameraResolution, val title: String, val fpsText: String)

/**
 * Вікно налаштувань: вибір роздільної здатності, високошвидкісний режим і (якщо камер кілька) вибір камери.
 * Створюється лише при першому натисканні на шестерню — це прискорює запуск.
 */
class SettingsPanel(context: Context) : FrameLayout(context) {

    private val dp = resources.displayMetrics.density

    private val highSpeedBox: View
    private val highSpeedTitle: TextView
    private val highSpeedHint: TextView
    private val highSpeedSwitch: Switch
    private val resolutionList: ListView
    private val cameraBox: View
    private val cameraPicker: TextView
    private val adapter = ResolutionAdapter()

    private var cameras: List<CameraDescriptor> = emptyList()
    private var selectedCameraId: String? = null
    private var listEnabled = true
    private var updating = false

    var onCloseRequested: (() -> Unit)? = null
    var onResolutionChosen: ((CameraResolution) -> Unit)? = null
    var onCameraChosen: ((CameraDescriptor) -> Unit)? = null

    /** Користувач увімкнув або вимкнув високошвидкісний режим. */
    var onHighSpeedChanged: ((Boolean) -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.settings_panel, this, true)

        findViewById<MaxWidthLinearLayout>(R.id.card).maxWidthPx = (MAX_CARD_WIDTH_DP * dp).toInt()
        findViewById<View>(R.id.dim).setOnClickListener { onCloseRequested?.invoke() }
        findViewById<Button>(R.id.close_button).setOnClickListener { onCloseRequested?.invoke() }

        highSpeedBox = findViewById(R.id.high_speed_box)
        highSpeedTitle = findViewById(R.id.high_speed_title)
        highSpeedHint = findViewById(R.id.high_speed_hint)
        highSpeedSwitch = findViewById(R.id.high_speed_switch)
        resolutionList = findViewById(R.id.resolution_list)
        cameraBox = findViewById(R.id.camera_box)
        cameraPicker = findViewById(R.id.camera_picker)

        val accent = resources.getColor(R.color.accent, context.theme)
        highSpeedSwitch.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        highSpeedSwitch.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, Color.argb(0x66, 0xFF, 0xFF, 0xFF)),
        )
        highSpeedSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updating) onHighSpeedChanged?.invoke(checked)
        }

        resolutionList.adapter = adapter
        resolutionList.setOnItemClickListener { _, _, position, _ ->
            if (updating || !listEnabled) return@setOnItemClickListener
            onResolutionChosen?.invoke(adapter.items[position].resolution)
        }

        cameraPicker.setOnClickListener { showCameraDialog() }
    }

    /** Заповнює списки поточними даними (викликається при відкритті та при зміні камери). [pageHeight] — у dp. */
    fun bind(
        resolutions: List<CameraResolution>,
        frameRates: Map<String, Double>?,
        selectedResolution: CameraResolution?,
        cameras: List<CameraDescriptor>,
        selectedCameraId: String?,
        pageHeight: Double,
        highSpeedFps: Int,
        highSpeedResolution: CameraResolution?,
        highSpeedOn: Boolean,
    ) {
        updating = true
        try {
            val highSpeedAvailable = highSpeedFps > 0
            highSpeedBox.visibility = if (highSpeedAvailable) VISIBLE else GONE
            if (highSpeedAvailable) {
                highSpeedTitle.text = context.getString(R.string.high_speed_title, highSpeedFps)
                highSpeedHint.text = if (highSpeedResolution != null) {
                    context.getString(R.string.high_speed_hint_with_frame, highSpeedResolution.width, highSpeedResolution.height)
                } else {
                    context.getString(R.string.high_speed_hint)
                }
                highSpeedSwitch.isChecked = highSpeedOn
            }

            // У цьому режимі розмір кадру диктує камера — вибір роздільності вимкнено.
            listEnabled = !(highSpeedAvailable && highSpeedOn)
            resolutionList.isEnabled = listEnabled
            resolutionList.alpha = if (listEnabled) 1f else 0.35f

            // Висота списку: усі пункти, але не більше ~55% висоти екрана (далі — прокрутка).
            // Рядок високошвидкісного режиму займає ~100 dp, тож список коротшає, щоб вибір камери не виходив за екран.
            val maxHeight = maxOf(ROW_HEIGHT_DP * 3, pageHeight * 0.55 - (if (highSpeedAvailable) 110 else 0))
            val listHeight = minOf(resolutions.size * ROW_HEIGHT_DP, maxHeight)
            resolutionList.layoutParams = resolutionList.layoutParams.apply { height = (listHeight * dp).toInt() }

            adapter.items = resolutions.map { ResolutionItem(it, it.displayName, formatFps(frameRates, it)) }
            adapter.selected = adapter.items.indexOfFirst { it.resolution == selectedResolution }
            adapter.notifyDataSetChanged()

            this.cameras = cameras
            this.selectedCameraId = selectedCameraId
            cameraBox.visibility = if (cameras.size > 1) VISIBLE else GONE
            cameraPicker.text = cameras.firstOrNull { it.id == selectedCameraId }?.name ?: ""
        } finally {
            updating = false
        }
    }

    private fun formatFps(frameRates: Map<String, Double>?, resolution: CameraResolution): String {
        val fps = frameRates?.get(resolution.key)
        return if (fps != null && fps >= 1) context.getString(R.string.fps_format, Math.round(fps).toInt()) else ""
    }

    private fun showCameraDialog() {
        if (cameras.isEmpty()) return

        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.camera_label)
            .setSingleChoiceItems(
                cameras.map { it.name }.toTypedArray(),
                cameras.indexOfFirst { it.id == selectedCameraId },
            ) { dialog, which ->
                dialog.dismiss()
                onCameraChosen?.invoke(cameras[which])
            }
            .show()
    }

    private class ResolutionAdapter : BaseAdapter() {
        var items: List<ResolutionItem> = emptyList()
        var selected = -1

        override fun getCount() = items.size

        override fun getItem(position: Int) = items[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_resolution, parent, false)

            val item = items[position]
            row.findViewById<TextView>(R.id.title).text = item.title
            row.findViewById<TextView>(R.id.fps).text = item.fpsText
            row.setBackgroundColor(
                if (position == selected) parent.resources.getColor(R.color.row_selected, parent.context.theme) else Color.TRANSPARENT,
            )
            return row
        }
    }

    private companion object {
        const val ROW_HEIGHT_DP = 48.0 // = layout/item_resolution.xml
        const val MAX_CARD_WIDTH_DP = 400
    }
}
