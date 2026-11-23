package com.smartdraw.project

import android.graphics.Color

data class PaintingProject(
    val id: String,
    var title: String = "Untitled Painting",
    var canvasWidth: Int = 1024,
    var canvasHeight: Int = 1024,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var referenceOpacity: Float = 0.3f,
    var isReferenceVisible: Boolean = true,
    var useReferenceColors: Boolean = true,
    var isRatchetEnabled: Boolean = false,
    var activeTool: String = "PENCIL",
    var brushSize: Float = 20.0f,
    var brushOpacity: Float = 1.0f,
    var manualBrushColor: Int = Color.BLACK,
    var layers: List<LayerMetadata> = emptyList(),
    var activeLayerId: String? = null
)
