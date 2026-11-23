package com.smartdraw.project

data class LayerMetadata(
    val id: String,
    var name: String = "Layer",
    var opacity: Float = 1.0f,
    var isVisible: Boolean = true
)
