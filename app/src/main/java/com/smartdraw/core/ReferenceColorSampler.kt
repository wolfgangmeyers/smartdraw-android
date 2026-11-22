package com.smartdraw.core

import android.graphics.Bitmap
import android.graphics.Color

class ReferenceColorSampler(
    var referenceBitmap: Bitmap? = null
) {
    fun sampleColor(x: Float, y: Float, defaultColor: Int = Color.BLACK): Int {
        val bitmap = referenceBitmap ?: return defaultColor
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return defaultColor

        val px = x.toInt().coerceIn(0, w - 1)
        val py = y.toInt().coerceIn(0, h - 1)
        return bitmap.getPixel(px, py)
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
