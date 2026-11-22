package com.smartdraw.core

import android.graphics.Bitmap
import android.graphics.Color

class ReferenceColorSampler(
    var referenceBitmap: Bitmap? = null,
    var canvasWidth: Float = 1024f,
    var canvasHeight: Float = 1024f
) {
    fun sampleColor(x: Float, y: Float, defaultColor: Int = Color.BLACK): Int {
        val bitmap = referenceBitmap ?: return defaultColor
        val rw = bitmap.width.toFloat()
        val rh = bitmap.height.toFloat()
        if (rw <= 0f || rh <= 0f || canvasWidth <= 0f || canvasHeight <= 0f) return defaultColor

        // Aspect ratio scaling & letterboxing mapping (SPECIFICATION.md §6.1)
        val scale = minOf(canvasWidth / rw, canvasHeight / rh)
        val drawnW = rw * scale
        val drawnH = rh * scale
        val offsetX = (canvasWidth - drawnW) * 0.5f
        val offsetY = (canvasHeight - drawnH) * 0.5f

        val imgX = ((x - offsetX) / scale).toInt()
        val imgY = ((y - offsetY) / scale).toInt()

        if (imgX !in 0 until bitmap.width || imgY !in 0 until bitmap.height) {
            return defaultColor
        }

        return bitmap.getPixel(imgX, imgY)
    }

    fun sampleColorNormalized(x: Float, y: Float, defaultColor: Int = Color.BLACK): FloatArray {
        val color = sampleColor(x, y, defaultColor)
        val a = Color.alpha(color) / 255.0f
        val r = Color.red(color) / 255.0f
        val g = Color.green(color) / 255.0f
        val b = Color.blue(color) / 255.0f
        return floatArrayOf(r, g, b, a)
    }
}
