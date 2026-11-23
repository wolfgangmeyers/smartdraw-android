package com.smartdraw

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.smartdraw.project.LayerMetadata
import kotlin.math.roundToInt

class LayerManagerBottomSheet : BottomSheetDialogFragment() {

    private lateinit var btnAddLayer: ImageButton
    private lateinit var btnDuplicateLayer: ImageButton
    private lateinit var btnMergeDown: ImageButton
    private lateinit var btnMoveUp: ImageButton
    private lateinit var btnMoveDown: ImageButton
    private lateinit var btnDeleteLayer: ImageButton
    private lateinit var textLayerCount: TextView
    private lateinit var recyclerLayers: RecyclerView

    private lateinit var adapter: LayerAdapter

    companion object {
        const val TAG = "LayerManagerBottomSheet"

        fun newInstance(): LayerManagerBottomSheet {
            return LayerManagerBottomSheet()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.modal_layer_manager, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnAddLayer = view.findViewById(R.id.btnAddLayer)
        btnDuplicateLayer = view.findViewById(R.id.btnDuplicateLayer)
        btnMergeDown = view.findViewById(R.id.btnMergeDown)
        btnMoveUp = view.findViewById(R.id.btnMoveUp)
        btnMoveDown = view.findViewById(R.id.btnMoveDown)
        btnDeleteLayer = view.findViewById(R.id.btnDeleteLayer)
        textLayerCount = view.findViewById(R.id.textLayerCount)
        recyclerLayers = view.findViewById(R.id.recyclerLayers)

        val drawView = (activity as? MainActivity)?.drawView

        recyclerLayers.layoutManager = LinearLayoutManager(requireContext())
        adapter = LayerAdapter(
            onLayerSelected = { id ->
                drawView?.selectLayer(id)
                refreshLayersUI()
            },
            onVisibilityToggled = { id, visible ->
                drawView?.setLayerVisibility(id, visible)
                refreshLayersUI()
            },
            onOpacityChanged = { id, opacity ->
                drawView?.setLayerOpacity(id, opacity)
            }
        )
        recyclerLayers.adapter = adapter

        btnAddLayer.setOnClickListener {
            drawView?.addLayer()
            refreshLayersUI()
        }

        btnDuplicateLayer.setOnClickListener {
            val activeId = drawView?.getActiveLayerId()
            if (activeId != null) {
                drawView.duplicateLayer(activeId)
                refreshLayersUI()
            }
        }

        btnMergeDown.setOnClickListener {
            val activeId = drawView?.getActiveLayerId()
            if (activeId != null) {
                drawView.mergeDown(activeId)
                refreshLayersUI()
            }
        }

        btnMoveUp.setOnClickListener {
            val activeId = drawView?.getActiveLayerId()
            if (activeId != null) {
                drawView.moveLayerUp(activeId)
                refreshLayersUI()
            }
        }

        btnMoveDown.setOnClickListener {
            val activeId = drawView?.getActiveLayerId()
            if (activeId != null) {
                drawView.moveLayerDown(activeId)
                refreshLayersUI()
            }
        }

        btnDeleteLayer.setOnClickListener {
            val activeId = drawView?.getActiveLayerId()
            if (activeId != null) {
                drawView.deleteLayer(activeId)
                refreshLayersUI()
            }
        }

        refreshLayersUI()
    }

    fun refreshLayersUI() {
        val drawView = (activity as? MainActivity)?.drawView ?: return
        val layers = drawView.getLayers()
        val activeId = drawView.getActiveLayerId()

        textLayerCount.text = "${layers.size} ${if (layers.size == 1) "layer" else "layers"}"

        val activeIdx = layers.indexOfFirst { it.id == activeId }

        // Action Toolbar Enable/Disable and Alpha State
        btnDeleteLayer.isEnabled = layers.size > 1
        btnDeleteLayer.alpha = if (layers.size > 1) 1.0f else 0.35f

        btnMergeDown.isEnabled = activeIdx > 0
        btnMergeDown.alpha = if (activeIdx > 0) 1.0f else 0.35f

        btnMoveUp.isEnabled = activeIdx in 0 until (layers.size - 1)
        btnMoveUp.alpha = if (btnMoveUp.isEnabled) 1.0f else 0.35f

        btnMoveDown.isEnabled = activeIdx > 0
        btnMoveDown.alpha = if (activeIdx > 0) 1.0f else 0.35f

        btnDuplicateLayer.isEnabled = activeIdx >= 0
        btnDuplicateLayer.alpha = if (btnDuplicateLayer.isEnabled) 1.0f else 0.35f

        // Display in natural top-to-bottom visual order (top layer at the top of list)
        adapter.submitList(layers.reversed(), activeId)
    }

    inner class LayerAdapter(
        private val onLayerSelected: (String) -> Unit,
        private val onVisibilityToggled: (String, Boolean) -> Unit,
        private val onOpacityChanged: (String, Float) -> Unit
    ) : RecyclerView.Adapter<LayerAdapter.LayerViewHolder>() {

        private val items = mutableListOf<LayerMetadata>()
        private var activeId: String? = null

        fun submitList(newItems: List<LayerMetadata>, newActiveId: String?) {
            items.clear()
            items.addAll(newItems)
            activeId = newActiveId
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LayerViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_layer, parent, false)
            return LayerViewHolder(v)
        }

        override fun onBindViewHolder(holder: LayerViewHolder, position: Int) {
            holder.bind(items[position], items[position].id == activeId)
        }

        override fun getItemCount(): Int = items.size

        inner class LayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cardLayer: LinearLayout = itemView.findViewById(R.id.cardLayer)
            private val indicatorActiveLayer: View = itemView.findViewById(R.id.indicatorActiveLayer)
            private val textLayerName: TextView = itemView.findViewById(R.id.textLayerName)
            private val textLayerOpacity: TextView = itemView.findViewById(R.id.textLayerOpacity)
            private val btnLayerVisibility: ImageButton = itemView.findViewById(R.id.btnLayerVisibility)
            private val sliderLayerOpacity: SeekBar = itemView.findViewById(R.id.sliderLayerOpacity)

            fun bind(layer: LayerMetadata, isActive: Boolean) {
                textLayerName.text = layer.name
                val opacityPercent = (layer.opacity * 100f).roundToInt().coerceIn(0, 100)
                textLayerOpacity.text = "$opacityPercent%"

                cardLayer.setBackgroundResource(
                    if (isActive) R.drawable.bg_tool_card_active else R.drawable.bg_tool_card_inactive
                )
                indicatorActiveLayer.visibility = if (isActive) View.VISIBLE else View.INVISIBLE

                // Visibility Icon & Tint
                if (layer.isVisible) {
                    btnLayerVisibility.setImageResource(R.drawable.ic_visibility)
                    btnLayerVisibility.setColorFilter(android.graphics.Color.WHITE)
                } else {
                    btnLayerVisibility.setImageResource(R.drawable.ic_visibility_off)
                    btnLayerVisibility.setColorFilter(android.graphics.Color.parseColor("#757575"))
                }

                // Opacity Slider
                sliderLayerOpacity.progress = opacityPercent
                sliderLayerOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            textLayerOpacity.text = "$progress%"
                            layer.opacity = progress / 100f
                            onOpacityChanged(layer.id, progress / 100f)
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })

                // Select Layer on Card Click
                cardLayer.setOnClickListener {
                    onLayerSelected(layer.id)
                }

                // Visibility Click
                btnLayerVisibility.setOnClickListener {
                    val newVisible = !layer.isVisible
                    layer.isVisible = newVisible
                    onVisibilityToggled(layer.id, newVisible)
                }
            }
        }
    }
}
