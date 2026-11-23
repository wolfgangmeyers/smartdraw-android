package com.smartdraw

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.smartdraw.core.ColorSelectionUtil
import com.smartdraw.ui.BrushPreviewView
import kotlin.math.roundToInt

class ToolSettingsBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onToolSelected(toolMode: SmartDrawView.ToolMode)
        fun onBrushSizeChanged(radius: Float)
        fun onBrushOpacityChanged(opacity: Float)
        fun onBrushColorChanged(color: Int) {}
        fun onEyedropperRequested() {}
        fun onAutoColorToggled(enabled: Boolean) {}
    }

    enum class ColorMode {
        RGB,
        HSL
    }

    var listener: Listener? = null
    var drawView: SmartDrawView? = null
    var currentTool: SmartDrawView.ToolMode = SmartDrawView.ToolMode.PENCIL
    var currentBrushRadius: Float = 16.0f
    var currentBrushOpacity: Float = 1.0f
    var currentBrushColor: Int = Color.BLACK
    var useReferenceColors: Boolean = false
    var colorMode: ColorMode = ColorMode.RGB

    private lateinit var cardToolPencil: LinearLayout
    private lateinit var cardToolSmudge: LinearLayout
    private lateinit var iconPencil: ImageView
    private lateinit var textPencil: TextView
    private lateinit var iconSmudge: ImageView
    private lateinit var textSmudge: TextView

    private lateinit var textSettingsSectionTitle: TextView
    private lateinit var brushPreview: BrushPreviewView
    private lateinit var textSizeValue: TextView
    private lateinit var sliderBrushSize: SeekBar
    private lateinit var textBrushOpacityValue: TextView
    private lateinit var sliderBrushOpacity: SeekBar

    // Color controls
    private lateinit var layoutAutoColorNotice: LinearLayout
    private lateinit var btnDisableAutoColor: LinearLayout
    private lateinit var layoutManualColorControls: LinearLayout
    private lateinit var colorSwatchPreview: View
    private lateinit var textColorHex: TextView
    private lateinit var btnEyedropper: LinearLayout

    private lateinit var btnModeRgb: TextView
    private lateinit var btnModeHsl: TextView

    private lateinit var layoutRgbSliders: LinearLayout
    private lateinit var textRedValue: TextView
    private lateinit var sliderRed: SeekBar
    private lateinit var textGreenValue: TextView
    private lateinit var sliderGreen: SeekBar
    private lateinit var textBlueValue: TextView
    private lateinit var sliderBlue: SeekBar

    private lateinit var layoutHslSliders: LinearLayout
    private lateinit var textHueValue: TextView
    private lateinit var sliderHue: SeekBar
    private lateinit var textSaturationValue: TextView
    private lateinit var sliderSaturation: SeekBar
    private lateinit var textLightnessValue: TextView
    private lateinit var sliderLightness: SeekBar

    private var isUpdatingSlidersInternally = false

    companion object {
        const val TAG = "ToolSettingsBottomSheet"
        const val MIN_BRUSH_RADIUS = 2.0f
        const val MAX_BRUSH_RADIUS = 100.0f

        fun newInstance(
            toolMode: SmartDrawView.ToolMode,
            brushRadius: Float,
            brushOpacity: Float,
            brushColor: Int = Color.BLACK,
            useReferenceColors: Boolean = false
        ): ToolSettingsBottomSheet {
            return ToolSettingsBottomSheet().apply {
                currentTool = toolMode
                currentBrushRadius = brushRadius
                currentBrushOpacity = brushOpacity
                currentBrushColor = brushColor
                this.useReferenceColors = useReferenceColors
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.modal_tool_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cardToolPencil = view.findViewById(R.id.cardToolPencil)
        cardToolSmudge = view.findViewById(R.id.cardToolSmudge)
        iconPencil = view.findViewById(R.id.iconPencil)
        textPencil = view.findViewById(R.id.textPencil)
        iconSmudge = view.findViewById(R.id.iconSmudge)
        textSmudge = view.findViewById(R.id.textSmudge)

        textSettingsSectionTitle = view.findViewById(R.id.textSettingsSectionTitle)
        brushPreview = view.findViewById(R.id.brushPreview)
        textSizeValue = view.findViewById(R.id.textSizeValue)
        sliderBrushSize = view.findViewById(R.id.sliderBrushSize)
        textBrushOpacityValue = view.findViewById(R.id.textBrushOpacityValue)
        sliderBrushOpacity = view.findViewById(R.id.sliderBrushOpacity)

        // Color Section Views
        layoutAutoColorNotice = view.findViewById(R.id.layoutAutoColorNotice)
        btnDisableAutoColor = view.findViewById(R.id.btnDisableAutoColor)
        layoutManualColorControls = view.findViewById(R.id.layoutManualColorControls)
        colorSwatchPreview = view.findViewById(R.id.colorSwatchPreview)
        textColorHex = view.findViewById(R.id.textColorHex)
        btnEyedropper = view.findViewById(R.id.btnEyedropper)

        btnModeRgb = view.findViewById(R.id.btnModeRgb)
        btnModeHsl = view.findViewById(R.id.btnModeHsl)

        layoutRgbSliders = view.findViewById(R.id.layoutRgbSliders)
        textRedValue = view.findViewById(R.id.textRedValue)
        sliderRed = view.findViewById(R.id.sliderRed)
        textGreenValue = view.findViewById(R.id.textGreenValue)
        sliderGreen = view.findViewById(R.id.sliderGreen)
        textBlueValue = view.findViewById(R.id.textBlueValue)
        sliderBlue = view.findViewById(R.id.sliderBlue)

        layoutHslSliders = view.findViewById(R.id.layoutHslSliders)
        textHueValue = view.findViewById(R.id.textHueValue)
        sliderHue = view.findViewById(R.id.sliderHue)
        textSaturationValue = view.findViewById(R.id.textSaturationValue)
        sliderSaturation = view.findViewById(R.id.sliderSaturation)
        textLightnessValue = view.findViewById(R.id.textLightnessValue)
        sliderLightness = view.findViewById(R.id.sliderLightness)

        // Initialize preview
        brushPreview.brushRadius = currentBrushRadius
        brushPreview.brushOpacity = currentBrushOpacity
        brushPreview.previewColor = if (useReferenceColors) Color.WHITE else currentBrushColor

        // Setup size slider (progress 0..98 maps to 2..100)
        val sizeProgress = (currentBrushRadius - MIN_BRUSH_RADIUS).roundToInt().coerceIn(0, 98)
        sliderBrushSize.progress = sizeProgress
        textSizeValue.text = "${currentBrushRadius.roundToInt()} px"

        // Setup opacity slider (progress 0..99 maps to 1..100%)
        val opacityPercent = (currentBrushOpacity * 100f).roundToInt().coerceIn(1, 100)
        sliderBrushOpacity.progress = opacityPercent - 1
        textBrushOpacityValue.text = "$opacityPercent%"

        updateToolSelectionUI()
        updateColorSectionUI()

        cardToolPencil.setOnClickListener {
            if (currentTool != SmartDrawView.ToolMode.PENCIL) {
                currentTool = SmartDrawView.ToolMode.PENCIL
                updateToolSelectionUI()
                listener?.onToolSelected(currentTool)
            }
        }

        cardToolSmudge.setOnClickListener {
            if (currentTool != SmartDrawView.ToolMode.SMUDGE) {
                currentTool = SmartDrawView.ToolMode.SMUDGE
                updateToolSelectionUI()
                listener?.onToolSelected(currentTool)
            }
        }

        sliderBrushSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val radius = progress + MIN_BRUSH_RADIUS
                currentBrushRadius = radius
                textSizeValue.text = "${radius.roundToInt()} px"
                brushPreview.brushRadius = radius
                listener?.onBrushSizeChanged(radius)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sliderBrushOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = progress + 1
                val opacity = percent / 100.0f
                currentBrushOpacity = opacity
                textBrushOpacityValue.text = "$percent%"
                brushPreview.brushOpacity = opacity
                listener?.onBrushOpacityChanged(opacity)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Mode switchers
        btnModeRgb.setOnClickListener {
            if (colorMode != ColorMode.RGB) {
                colorMode = ColorMode.RGB
                updateColorModeTabs()
            }
        }

        btnModeHsl.setOnClickListener {
            if (colorMode != ColorMode.HSL) {
                colorMode = ColorMode.HSL
                updateColorModeTabs()
            }
        }

        // Eyedropper button
        btnEyedropper.setOnClickListener {
            dismiss()
            drawView?.isEyedropperActive = true
            listener?.onEyedropperRequested()
        }

        // Disable Auto Color button (inside Auto Color notice)
        btnDisableAutoColor.setOnClickListener {
            useReferenceColors = false
            drawView?.useReferenceColors = false
            listener?.onAutoColorToggled(false)
            updateColorSectionUI()
            brushPreview.previewColor = currentBrushColor
        }

        // RGB Slider Listeners
        val rgbListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingSlidersInternally) return
                val r = sliderRed.progress
                val g = sliderGreen.progress
                val b = sliderBlue.progress
                val newColor = Color.rgb(r, g, b)
                onColorChangedInternal(newColor, updateRgbSliders = false, updateHslSliders = true)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        sliderRed.setOnSeekBarChangeListener(rgbListener)
        sliderGreen.setOnSeekBarChangeListener(rgbListener)
        sliderBlue.setOnSeekBarChangeListener(rgbListener)

        // HSL Slider Listeners
        val hslListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingSlidersInternally) return
                val h = sliderHue.progress.toFloat()
                val s = sliderSaturation.progress / 100f
                val l = sliderLightness.progress / 100f
                val newColor = ColorSelectionUtil.hslToColor(h, s, l)
                onColorChangedInternal(newColor, updateRgbSliders = true, updateHslSliders = false)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        sliderHue.setOnSeekBarChangeListener(hslListener)
        sliderSaturation.setOnSeekBarChangeListener(hslListener)
        sliderLightness.setOnSeekBarChangeListener(hslListener)
    }

    private fun onColorChangedInternal(
        newColor: Int,
        updateRgbSliders: Boolean,
        updateHslSliders: Boolean
    ) {
        currentBrushColor = newColor
        updateSwatchAndHex(newColor)

        if (!useReferenceColors) {
            brushPreview.previewColor = newColor
        }

        drawView?.brushColor = newColor
        listener?.onBrushColorChanged(newColor)

        isUpdatingSlidersInternally = true
        if (updateRgbSliders) {
            val r = Color.red(newColor)
            val g = Color.green(newColor)
            val b = Color.blue(newColor)
            sliderRed.progress = r
            sliderGreen.progress = g
            sliderBlue.progress = b
            textRedValue.text = "$r"
            textGreenValue.text = "$g"
            textBlueValue.text = "$b"
        }
        if (updateHslSliders) {
            val hsl = ColorSelectionUtil.colorToHsl(newColor)
            val h = hsl[0].roundToInt().coerceIn(0, 360)
            val s = (hsl[1] * 100f).roundToInt().coerceIn(0, 100)
            val l = (hsl[2] * 100f).roundToInt().coerceIn(0, 100)
            sliderHue.progress = h
            sliderSaturation.progress = s
            sliderLightness.progress = l
            textHueValue.text = "$h°"
            textSaturationValue.text = "$s%"
            textLightnessValue.text = "$l%"
        }
        isUpdatingSlidersInternally = false
    }

    private fun updateSwatchAndHex(color: Int) {
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(2, Color.WHITE)
        }
        colorSwatchPreview.background = drawable
        textColorHex.text = ColorSelectionUtil.colorToHex(color)
    }

    private fun updateColorSectionUI() {
        if (useReferenceColors) {
            layoutAutoColorNotice.visibility = View.VISIBLE
            layoutManualColorControls.visibility = View.GONE
        } else {
            layoutAutoColorNotice.visibility = View.GONE
            layoutManualColorControls.visibility = View.VISIBLE
            updateSwatchAndHex(currentBrushColor)
            updateColorModeTabs()
            syncSlidersFromColor(currentBrushColor)
        }
    }

    private fun updateColorModeTabs() {
        if (colorMode == ColorMode.RGB) {
            btnModeRgb.setBackgroundResource(R.drawable.bg_tool_button_active)
            btnModeRgb.setTextColor(0xFFFFFFFF.toInt())
            btnModeHsl.setBackgroundResource(R.drawable.bg_tool_button_inactive)
            btnModeHsl.setTextColor(0xFFB0B0B0.toInt())

            layoutRgbSliders.visibility = View.VISIBLE
            layoutHslSliders.visibility = View.GONE
        } else {
            btnModeRgb.setBackgroundResource(R.drawable.bg_tool_button_inactive)
            btnModeRgb.setTextColor(0xFFB0B0B0.toInt())
            btnModeHsl.setBackgroundResource(R.drawable.bg_tool_button_active)
            btnModeHsl.setTextColor(0xFFFFFFFF.toInt())

            layoutRgbSliders.visibility = View.GONE
            layoutHslSliders.visibility = View.VISIBLE
        }
    }

    private fun syncSlidersFromColor(color: Int) {
        isUpdatingSlidersInternally = true
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        sliderRed.progress = r
        sliderGreen.progress = g
        sliderBlue.progress = b
        textRedValue.text = "$r"
        textGreenValue.text = "$g"
        textBlueValue.text = "$b"

        val hsl = ColorSelectionUtil.colorToHsl(color)
        val h = hsl[0].roundToInt().coerceIn(0, 360)
        val s = (hsl[1] * 100f).roundToInt().coerceIn(0, 100)
        val l = (hsl[2] * 100f).roundToInt().coerceIn(0, 100)
        sliderHue.progress = h
        sliderSaturation.progress = s
        sliderLightness.progress = l
        textHueValue.text = "$h°"
        textSaturationValue.text = "$s%"
        textLightnessValue.text = "$l%"
        isUpdatingSlidersInternally = false
    }

    private fun updateToolSelectionUI() {
        if (currentTool == SmartDrawView.ToolMode.PENCIL) {
            cardToolPencil.setBackgroundResource(R.drawable.bg_tool_card_active)
            iconPencil.setColorFilter(0xFF2979FF.toInt())
            textPencil.setTextColor(0xFFFFFFFF.toInt())

            cardToolSmudge.setBackgroundResource(R.drawable.bg_tool_card_inactive)
            iconSmudge.setColorFilter(0xFFB0B0B0.toInt())
            textSmudge.setTextColor(0xFFB0B0B0.toInt())

            textSettingsSectionTitle.text = "PENCIL SETTINGS"
        } else {
            cardToolPencil.setBackgroundResource(R.drawable.bg_tool_card_inactive)
            iconPencil.setColorFilter(0xFFB0B0B0.toInt())
            textPencil.setTextColor(0xFFB0B0B0.toInt())

            cardToolSmudge.setBackgroundResource(R.drawable.bg_tool_card_active)
            iconSmudge.setColorFilter(0xFF2979FF.toInt())
            textSmudge.setTextColor(0xFFFFFFFF.toInt())

            textSettingsSectionTitle.text = "SMUDGE SETTINGS"
        }
    }
}
