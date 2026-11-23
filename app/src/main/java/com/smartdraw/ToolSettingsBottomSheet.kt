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
import kotlin.math.roundToInt

class ToolSettingsBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onToolSelected(toolMode: SmartDrawView.ToolMode)
        fun onBrushSizeChanged(radius: Float)
        fun onBrushOpacityChanged(opacity: Float)
    }

    var listener: Listener? = null
    var drawView: SmartDrawView? = null
    var currentTool: SmartDrawView.ToolMode = SmartDrawView.ToolMode.PENCIL
    var currentBrushRadius: Float = 16.0f
    var currentBrushOpacity: Float = 1.0f
    var currentBrushColor: Int = Color.BLACK
    var useReferenceColors: Boolean = false

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
