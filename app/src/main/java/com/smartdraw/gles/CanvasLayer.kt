package com.smartdraw.gles

import java.util.UUID

class CanvasLayer(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Layer",
    var opacity: Float = 1.0f,
    var isVisible: Boolean = true,
    var fbo: GlesFbo
) {
    fun release() = fbo.release()
}
