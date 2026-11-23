package com.smartdraw.core

import android.graphics.Color
import androidx.core.graphics.ColorUtils

object ColorSelectionUtil {

    fun colorToHex(color: Int): String {
        return String.format("#%06X", 0xFFFFFF and color)
    }

    fun hexToColor(hex: String, defaultColor: Int = Color.BLACK): Int {
        return try {
            val cleanHex = if (hex.startsWith("#")) hex else "#$hex"
            Color.parseColor(cleanHex)
        } catch (e: Exception) {
            defaultColor
        }
    }

    fun colorToHsl(color: Int): FloatArray {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        return hsl
    }

    fun hslToColor(hue: Float, saturation: Float, lightness: Float): Int {
        val clampedH = hue.coerceIn(0f, 360f)
        val clampedS = saturation.coerceIn(0f, 1f)
        val clampedL = lightness.coerceIn(0f, 1f)
        return ColorUtils.HSLToColor(floatArrayOf(clampedH, clampedS, clampedL))
    }

    fun rgbToColor(r: Int, g: Int, b: Int): Int {
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }
}
