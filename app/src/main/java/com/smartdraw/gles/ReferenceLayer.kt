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

    fun sampleColor(
        canvasX: Float,
        canvasY: Float,
        canvasWidth: Float = 1024f,
        canvasHeight: Float = 1024f,
        defaultColor: Int = Color.BLACK
    ): Int {
        val b = bitmap ?: return defaultColor
        val rw = b.width.toFloat()
        val rh = b.height.toFloat()
        if (rw <= 0f || rh <= 0f || canvasWidth <= 0f || canvasHeight <= 0f) return defaultColor

        // Letterbox / aspect-fit mapping from canvas to reference bitmap
        val scale = minOf(canvasWidth / rw, canvasHeight / rh)
        val drawnW = rw * scale
        val drawnH = rh * scale
        val offsetX = (canvasWidth - drawnW) * 0.5f
        val offsetY = (canvasHeight - drawnH) * 0.5f

        val imgX = ((canvasX - offsetX) / scale).toInt()
        val imgY = ((canvasY - offsetY) / scale).toInt()

        if (imgX !in 0 until b.width || imgY !in 0 until b.height) {
            return defaultColor
        }

        return b.getPixel(imgX, imgY)
    }

    fun sampleColorNormalized(
        canvasX: Float,
        canvasY: Float,
        canvasWidth: Float = 1024f,
        canvasHeight: Float = 1024f,
        defaultColor: Int = Color.BLACK
    ): FloatArray {
        val color = sampleColor(canvasX, canvasY, canvasWidth, canvasHeight, defaultColor)
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
