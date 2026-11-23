package com.smartdraw

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.smartdraw.ui.BrushPreviewView
import java.util.Locale
import kotlin.math.roundToInt

class ToolSettingsBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onToolSelected(toolMode: SmartDrawView.ToolMode)
        fun onBrushSizeChanged(radius: Float)
        fun onBrushOpacityChanged(opacity: Float)
        fun getToolRadius(toolMode: SmartDrawView.ToolMode): Float = 20.0f
        fun getToolOpacity(toolMode: SmartDrawView.ToolMode): Float = 1.0f
    }

    var listener: Listener? = null
    var currentTool: SmartDrawView.ToolMode = SmartDrawView.ToolMode.PAINTBRUSH
    var currentBrushRadius: Float = 16.0f
    var currentBrushOpacity: Float = 1.0f
    var currentBrushColor: Int = Color.BLACK
    var useReferenceColors: Boolean = false

    private lateinit var cardToolBrush: LinearLayout
    private lateinit var cardToolPen: LinearLayout
    private lateinit var cardToolSmudge: LinearLayout

    private lateinit var iconBrush: ImageView
    private lateinit var textBrush: TextView
    private lateinit var iconPen: ImageView
    private lateinit var textPen: TextView
    private lateinit var iconSmudge: ImageView
    private lateinit var textSmudge: TextView

    private lateinit var textSettingsSectionTitle: TextView
    private lateinit var brushPreview: BrushPreviewView
    private lateinit var textSizeValue: TextView
    private lateinit var sliderBrushSize: SeekBar
    private lateinit var textBrushOpacityValue: TextView
    private lateinit var sliderBrushOpacity: SeekBar

    companion object {
        const val TAG = "ToolSettingsBottomSheet"
        const val MIN_BRUSH_RADIUS = 0.5f
        const val MAX_BRUSH_RADIUS = 100.0f
        const val BRUSH_RADIUS_STEP = 0.5f
        const val MAX_SLIDER_PROGRESS = 199 // 0..199 maps to 0.5..100.0 in 0.5px steps

        fun formatRadiusText(radius: Float): String {
            return if (radius % 1.0f == 0f) {
                "${radius.toInt()} px"
            } else {
                "${String.format(Locale.US, "%.1f", radius)} px"
            }
        }

        fun radiusToProgress(radius: Float): Int {
            return ((radius / BRUSH_RADIUS_STEP).roundToInt() - 1).coerceIn(0, MAX_SLIDER_PROGRESS)
        }

        fun progressToRadius(progress: Int): Float {
            return (progress + 1) * BRUSH_RADIUS_STEP
        }

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

        cardToolBrush = view.findViewById(R.id.cardToolBrush)
        cardToolPen = view.findViewById(R.id.cardToolPen)
        cardToolSmudge = view.findViewById(R.id.cardToolSmudge)

        iconBrush = view.findViewById(R.id.iconBrush)
        textBrush = view.findViewById(R.id.textBrush)
        iconPen = view.findViewById(R.id.iconPen)
        textPen = view.findViewById(R.id.textPen)
        iconSmudge = view.findViewById(R.id.iconSmudge)
        textSmudge = view.findViewById(R.id.textSmudge)

        textSettingsSectionTitle = view.findViewById(R.id.textSettingsSectionTitle)
        brushPreview = view.findViewById(R.id.brushPreview)
        textSizeValue = view.findViewById(R.id.textSizeValue)
        sliderBrushSize = view.findViewById(R.id.sliderBrushSize)
        textBrushOpacityValue = view.findViewById(R.id.textBrushOpacityValue)
        sliderBrushOpacity = view.findViewById(R.id.sliderBrushOpacity)

        // Initialize preview
        brushPreview.brushRadius = currentBrushRadius
        brushPreview.brushOpacity = currentBrushOpacity
        brushPreview.previewColor = if (useReferenceColors) Color.WHITE else currentBrushColor

        // Setup size slider (progress 0..199 maps to 0.5..100.0 px)
        sliderBrushSize.max = MAX_SLIDER_PROGRESS
        val sizeProgress = radiusToProgress(currentBrushRadius)
        sliderBrushSize.progress = sizeProgress
        textSizeValue.text = formatRadiusText(currentBrushRadius)

        // Setup opacity slider (progress 0..99 maps to 1..100%)
        val opacityPercent = (currentBrushOpacity * 100f).roundToInt().coerceIn(1, 100)
        sliderBrushOpacity.progress = opacityPercent - 1
        textBrushOpacityValue.text = "$opacityPercent%"

        updateToolSelectionUI()

        cardToolBrush.setOnClickListener {
            if (currentTool != SmartDrawView.ToolMode.PAINTBRUSH) {
                selectTool(SmartDrawView.ToolMode.PAINTBRUSH)
            }
        }

        cardToolPen.setOnClickListener {
            if (currentTool != SmartDrawView.ToolMode.PEN) {
                selectTool(SmartDrawView.ToolMode.PEN)
            }
        }

        cardToolSmudge.setOnClickListener {
            if (currentTool != SmartDrawView.ToolMode.SMUDGE) {
                selectTool(SmartDrawView.ToolMode.SMUDGE)
            }
        }

        sliderBrushSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val radius = progressToRadius(progress)
                currentBrushRadius = radius
                textSizeValue.text = formatRadiusText(radius)
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
    }

    private fun selectTool(toolMode: SmartDrawView.ToolMode) {
        currentTool = toolMode
        updateToolSelectionUI()
        listener?.onToolSelected(currentTool)
        val r = listener?.getToolRadius(toolMode) ?: currentBrushRadius
        val o = listener?.getToolOpacity(toolMode) ?: currentBrushOpacity
        updateToolSettings(r, o)
    }

    fun updateToolSettings(radius: Float, opacity: Float) {
        currentBrushRadius = radius
        currentBrushOpacity = opacity

        val sizeProgress = radiusToProgress(radius)
        sliderBrushSize.progress = sizeProgress
        textSizeValue.text = formatRadiusText(radius)
        brushPreview.brushRadius = radius

        val opacityPercent = (opacity * 100f).roundToInt().coerceIn(1, 100)
        sliderBrushOpacity.progress = opacityPercent - 1
        textBrushOpacityValue.text = "$opacityPercent%"
        brushPreview.brushOpacity = opacity
    }

    private fun updateToolSelectionUI() {
        // Reset all cards to inactive style
        cardToolBrush.setBackgroundResource(R.drawable.bg_tool_card_inactive)
        iconBrush.setColorFilter(0xFFB0B0B0.toInt())
        textBrush.setTextColor(0xFFB0B0B0.toInt())

        cardToolPen.setBackgroundResource(R.drawable.bg_tool_card_inactive)
        iconPen.setColorFilter(0xFFB0B0B0.toInt())
        textPen.setTextColor(0xFFB0B0B0.toInt())

        cardToolSmudge.setBackgroundResource(R.drawable.bg_tool_card_inactive)
        iconSmudge.setColorFilter(0xFFB0B0B0.toInt())
        textSmudge.setTextColor(0xFFB0B0B0.toInt())

        when (currentTool) {
            SmartDrawView.ToolMode.PAINTBRUSH -> {
                cardToolBrush.setBackgroundResource(R.drawable.bg_tool_card_active)
                iconBrush.setColorFilter(0xFF2979FF.toInt())
                textBrush.setTextColor(0xFFFFFFFF.toInt())
                textSettingsSectionTitle.text = "PAINTBRUSH SETTINGS"
            }
            SmartDrawView.ToolMode.PEN -> {
                cardToolPen.setBackgroundResource(R.drawable.bg_tool_card_active)
                iconPen.setColorFilter(0xFF2979FF.toInt())
                textPen.setTextColor(0xFFFFFFFF.toInt())
                textSettingsSectionTitle.text = "PEN SETTINGS"
            }
            SmartDrawView.ToolMode.SMUDGE -> {
                cardToolSmudge.setBackgroundResource(R.drawable.bg_tool_card_active)
                iconSmudge.setColorFilter(0xFF2979FF.toInt())
                textSmudge.setTextColor(0xFFFFFFFF.toInt())
                textSettingsSectionTitle.text = "SMUDGE SETTINGS"
            }
        }
    }
}
