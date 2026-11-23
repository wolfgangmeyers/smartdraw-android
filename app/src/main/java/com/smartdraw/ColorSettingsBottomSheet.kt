package com.smartdraw

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.smartdraw.core.ColorSelectionUtil
import kotlin.math.roundToInt

class ColorSettingsBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onBrushColorChanged(color: Int)
        fun onAutoColorToggled(enabled: Boolean)
        fun onEyedropperRequested()
    }

    enum class SliderMode {
        RGB,
        HSL
    }

    var listener: Listener? = null
    var drawView: SmartDrawView? = null
    var currentBrushColor: Int = Color.BLACK
    var useReferenceColors: Boolean = false
    var referenceStatusText: String = "Loaded (Ready)"
    var sliderMode: SliderMode = SliderMode.RGB

    private lateinit var btnCloseColorSettings: ImageButton
    private lateinit var btnModeAutoColor: TextView
    private lateinit var btnModeManualColor: TextView

    // Auto Color Content
    private lateinit var layoutAutoColorContent: LinearLayout
    private lateinit var textReferenceStatus: TextView
    private lateinit var btnSwitchToManual: LinearLayout

    // Manual Color Content
    private lateinit var layoutManualColorContent: LinearLayout
    private lateinit var colorSwatchPreview: View
    private lateinit var textColorHex: TextView
    private lateinit var btnEyedropperSheet: LinearLayout

    // RGB / HSL Switcher
    private lateinit var btnModeRgb: TextView
    private lateinit var btnModeHsl: TextView

    // RGB Sliders
    private lateinit var layoutRgbSliders: LinearLayout
    private lateinit var textRedValue: TextView
    private lateinit var sliderRed: SeekBar
    private lateinit var textGreenValue: TextView
    private lateinit var sliderGreen: SeekBar
    private lateinit var textBlueValue: TextView
    private lateinit var sliderBlue: SeekBar

    // HSL Sliders
    private lateinit var layoutHslSliders: LinearLayout
    private lateinit var textHueValue: TextView
    private lateinit var sliderHue: SeekBar
    private lateinit var textSaturationValue: TextView
    private lateinit var sliderSaturation: SeekBar
    private lateinit var textLightnessValue: TextView
    private lateinit var sliderLightness: SeekBar

    private var isUpdatingSlidersInternally = false

    companion object {
        const val TAG = "ColorSettingsBottomSheet"

        fun newInstance(
            brushColor: Int = Color.BLACK,
            useReferenceColors: Boolean = false,
            referenceStatus: String = "Loaded (Ready)"
        ): ColorSettingsBottomSheet {
            return ColorSettingsBottomSheet().apply {
                currentBrushColor = brushColor
                this.useReferenceColors = useReferenceColors
                this.referenceStatusText = referenceStatus
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.modal_color_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnCloseColorSettings = view.findViewById(R.id.btnCloseColorSettings)
        btnModeAutoColor = view.findViewById(R.id.btnModeAutoColor)
        btnModeManualColor = view.findViewById(R.id.btnModeManualColor)

        layoutAutoColorContent = view.findViewById(R.id.layoutAutoColorContent)
        textReferenceStatus = view.findViewById(R.id.textReferenceStatus)
        btnSwitchToManual = view.findViewById(R.id.btnSwitchToManual)

        layoutManualColorContent = view.findViewById(R.id.layoutManualColorContent)
        colorSwatchPreview = view.findViewById(R.id.colorSwatchPreview)
        textColorHex = view.findViewById(R.id.textColorHex)
        btnEyedropperSheet = view.findViewById(R.id.btnEyedropperSheet)

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

        textReferenceStatus.text = referenceStatusText

        updateModeUI()

        btnCloseColorSettings.setOnClickListener {
            dismiss()
        }

        btnModeAutoColor.setOnClickListener {
            if (!useReferenceColors) {
                useReferenceColors = true
                drawView?.useReferenceColors = true
                listener?.onAutoColorToggled(true)
                updateModeUI()
            }
        }

        btnModeManualColor.setOnClickListener {
            if (useReferenceColors) {
                useReferenceColors = false
                drawView?.useReferenceColors = false
                listener?.onAutoColorToggled(false)
                updateModeUI()
            }
        }

        btnSwitchToManual.setOnClickListener {
            useReferenceColors = false
            drawView?.useReferenceColors = false
            listener?.onAutoColorToggled(false)
            updateModeUI()
        }

        btnEyedropperSheet.setOnClickListener {
            dismiss()
            drawView?.isEyedropperActive = true
            listener?.onEyedropperRequested()
        }

        btnModeRgb.setOnClickListener {
            if (sliderMode != SliderMode.RGB) {
                sliderMode = SliderMode.RGB
                updateSliderTabs()
            }
        }

        btnModeHsl.setOnClickListener {
            if (sliderMode != SliderMode.HSL) {
                sliderMode = SliderMode.HSL
                updateSliderTabs()
            }
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

    private fun updateModeUI() {
        if (useReferenceColors) {
            btnModeAutoColor.setBackgroundResource(R.drawable.bg_tool_button_active)
            btnModeAutoColor.setTextColor(0xFFFFFFFF.toInt())
            btnModeManualColor.setBackgroundResource(R.drawable.bg_tool_button_inactive)
            btnModeManualColor.setTextColor(0xFFB0B0B0.toInt())

            layoutAutoColorContent.visibility = View.VISIBLE
            layoutManualColorContent.visibility = View.GONE
        } else {
            btnModeAutoColor.setBackgroundResource(R.drawable.bg_tool_button_inactive)
            btnModeAutoColor.setTextColor(0xFFB0B0B0.toInt())
            btnModeManualColor.setBackgroundResource(R.drawable.bg_tool_button_active)
            btnModeManualColor.setTextColor(0xFFFFFFFF.toInt())

            layoutAutoColorContent.visibility = View.GONE
            layoutManualColorContent.visibility = View.VISIBLE

            updateSwatchAndHex(currentBrushColor)
            updateSliderTabs()
            syncSlidersFromColor(currentBrushColor)
        }
    }

    private fun updateSliderTabs() {
        if (sliderMode == SliderMode.RGB) {
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

    private fun onColorChangedInternal(
        newColor: Int,
        updateRgbSliders: Boolean,
        updateHslSliders: Boolean
    ) {
        currentBrushColor = newColor
        updateSwatchAndHex(newColor)

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
}
