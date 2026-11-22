package com.smartdraw.gles

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.opengl.GLES30
import android.opengl.GLUtils

class ReferenceLayer {
    var textureId: Int = 0
        private set

    var bitmap: Bitmap? = null
        private set

    var opacity: Float = 0.3f
    var isVisible: Boolean = true

    fun setBitmap(source: Bitmap) {
        bitmap = source
        updateTexture()
    }

    fun updateTexture() {
        val src = bitmap ?: return

        // Flip vertically for OpenGL texture coordinate convention
        val matrix = Matrix().apply { postScale(1.0f, -1.0f) }
        val flipped = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)

        if (textureId == 0) {
            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            textureId = textures[0]
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, flipped, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        if (flipped != src) {
            flipped.recycle()
        }
    }

    fun sampleColor(x: Float, y: Float, defaultColor: Int = Color.BLACK): Int {
        val b = bitmap ?: return defaultColor
        val w = b.width
        val h = b.height
        if (w <= 0 || h <= 0) return defaultColor
        val px = x.toInt().coerceIn(0, w - 1)
        val py = y.toInt().coerceIn(0, h - 1)
        return b.getPixel(px, py)
    }

    fun sampleColorNormalized(x: Float, y: Float, defaultColor: Int = Color.BLACK): FloatArray {
        val color = sampleColor(x, y, defaultColor)
        val a = Color.alpha(color) / 255.0f
        val r = Color.red(color) / 255.0f
        val g = Color.green(color) / 255.0f
        val b = Color.blue(color) / 255.0f
        return floatArrayOf(r, g, b, a)
    }

    fun release() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }
}
