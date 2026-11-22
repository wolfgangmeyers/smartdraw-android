package com.smartdraw

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

class MoreOptionsBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onChangeSourceImageClicked()
        fun onOpacityChanged(opacityPercent: Int)
        fun onToggleSourceVisibilityClicked(currentlyVisible: Boolean)
        fun onClearPaintingClicked()
        fun onResetCanvasViewClicked()
        fun onRunTestHarnessClicked()
    }

    var listener: Listener? = null
    var currentOpacityPercent: Int = 30
    var isSourceVisible: Boolean = true

    private lateinit var textOpacityValue: TextView
    private lateinit var sliderOpacity: SeekBar
    private lateinit var btnToggleSourceVisibility: MaterialButton

    companion object {
        const val TAG = "MoreOptionsBottomSheet"

        fun newInstance(opacityPercent: Int, isSourceVisible: Boolean): MoreOptionsBottomSheet {
            return MoreOptionsBottomSheet().apply {
                currentOpacityPercent = opacityPercent
                this.isSourceVisible = isSourceVisible
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.modal_more_options, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnChangeSourceImage: MaterialButton = view.findViewById(R.id.btnChangeSourceImage)
        textOpacityValue = view.findViewById(R.id.textOpacityValue)
        sliderOpacity = view.findViewById(R.id.sliderOpacity)
        btnToggleSourceVisibility = view.findViewById(R.id.btnToggleSourceVisibility)
        val btnClearPainting: MaterialButton = view.findViewById(R.id.btnClearPainting)
        val btnResetCanvasView: MaterialButton = view.findViewById(R.id.btnResetCanvasView)
        val btnRunHarnessModal: MaterialButton = view.findViewById(R.id.btnRunHarnessModal)

        // Set initial states
        sliderOpacity.progress = currentOpacityPercent
        textOpacityValue.text = "$currentOpacityPercent%"
        updateVisibilityButtonUI()

        btnChangeSourceImage.setOnClickListener {
            dismiss()
            listener?.onChangeSourceImageClicked()
        }

        sliderOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentOpacityPercent = progress
                textOpacityValue.text = "$progress%"
                if (progress > 0 && !isSourceVisible) {
                    isSourceVisible = true
                    updateVisibilityButtonUI()
                }
                listener?.onOpacityChanged(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnToggleSourceVisibility.setOnClickListener {
            isSourceVisible = !isSourceVisible
            updateVisibilityButtonUI()
            listener?.onToggleSourceVisibilityClicked(isSourceVisible)
        }

        btnClearPainting.setOnClickListener {
            dismiss()
            listener?.onClearPaintingClicked()
        }

        btnResetCanvasView.setOnClickListener {
            dismiss()
            listener?.onResetCanvasViewClicked()
        }

        btnRunHarnessModal.setOnClickListener {
            dismiss()
            listener?.onRunTestHarnessClicked()
        }
    }

    private fun updateVisibilityButtonUI() {
        if (isSourceVisible) {
            btnToggleSourceVisibility.text = "Hide Source Image"
            btnToggleSourceVisibility.setIconResource(R.drawable.ic_visibility_off)
        } else {
            btnToggleSourceVisibility.text = "Show Source Image"
            btnToggleSourceVisibility.setIconResource(R.drawable.ic_visibility)
        }
    }
}
