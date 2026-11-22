package com.smartdraw.gles

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlesFbo(val width: Int, val height: Int) {
    var framebufferId: Int = 0
        private set
    var textureId: Int = 0
        private set

    init {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )

        val framebuffers = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffers, 0)
        framebufferId = framebuffers[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            textureId,
            0
        )

        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Framebuffer not complete: status = 0x${Integer.toHexString(status)}")
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES30.glViewport(0, 0, width, height)
    }

    fun unbind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * Reads a sub-rectangle from this FBO into an IntArray of ARGB pixels with top-left origin.
     * x and y are in top-left canvas coordinates [0, width), [0, height).
     */
    fun readSubRect(x: Int, y: Int, w: Int, h: Int): IntArray {
        val outPixels = IntArray(w * h)
        if (w <= 0 || h <= 0) return outPixels

        val glX = x.coerceIn(0, width - 1)
        val glY = (height - (y + h)).coerceIn(0, height - 1)
        val readW = w.coerceAtMost(width - glX)
        val readH = h.coerceAtMost(height - glY)

        bind()
        val buffer = ByteBuffer.allocateDirect(readW * readH * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(glX, glY, readW, readH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        unbind()

        buffer.rewind()
        val intBuf = buffer.asIntBuffer()
        val temp = IntArray(readW * readH)
        intBuf.get(temp)

        // glY is at the bottom of the sub-rect in OpenGL window coordinates.
        // In GLES, row 0 of `temp` is glY (bottom), row (readH - 1) is top.
        // Canvas top-left coordinates need row 0 to be top.
        for (row in 0 until readH) {
            val srcRow = (readH - 1 - row) * readW
            val dstRow = row * w
            for (col in 0 until readW) {
                val rgba = temp[srcRow + col]
                val r = rgba and 0xFF
                val g = (rgba shr 8) and 0xFF
                val b = (rgba shr 16) and 0xFF
                val a = (rgba shr 24) and 0xFF
                outPixels[dstRow + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return outPixels
    }

    fun readBitmap(): Bitmap {
        bind()
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        unbind()

        // Flip Y because OpenGL coordinates have (0,0) at lower-left, while Bitmap has (0,0) at upper-left
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixelArray = IntArray(width * height)
        buffer.rewind()
        val byteBuf = buffer.asIntBuffer()
        val temp = IntArray(width * height)
        byteBuf.get(temp)

        for (y in 0 until height) {
            val srcRow = (height - 1 - y) * width
            val dstRow = y * width
            for (x in 0 until width) {
                val rgba = temp[srcRow + x]
                // RGBA -> ARGB byte swapping if needed
                // In nativeOrder, on little-endian, GL_RGBA unsigned byte produces 0xAABBGGRR in Int
                val r = rgba and 0xFF
                val g = (rgba shr 8) and 0xFF
                val b = (rgba shr 16) and 0xFF
                val a = (rgba shr 24) and 0xFF
                pixelArray[dstRow + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bitmap.setPixels(pixelArray, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun loadBitmap(source: Bitmap) {
        if (source.isRecycled) return

        val flipped = try {
            val matrix = Matrix().apply { postScale(1.0f, -1.0f) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } catch (e: Exception) {
            // Fallback safe software canvas draw if color space or hardware bitmap issue
            val safe = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(safe)
            val matrix = Matrix().apply {
                postScale(1.0f, -1.0f)
                postTranslate(0f, source.height.toFloat())
            }
            canvas.drawBitmap(source, matrix, null)
            safe
        }

        val scaled = if (flipped.width != width || flipped.height != height) {
            Bitmap.createScaledBitmap(flipped, width, height, true)
        } else {
            flipped
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, scaled, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        if (scaled != flipped && scaled != source) {
            scaled.recycle()
        }
        if (flipped != source) {
            flipped.recycle()
        }
    }

    fun release() {
        if (framebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
            framebufferId = 0
        }
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }
}
