package com.smartdraw.gles

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.opengl.GLES30
import android.opengl.GLUtils
import com.smartdraw.core.Point2D
import kotlin.math.max
import kotlin.math.min

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

        // Normalize canvas coordinates (1024x1024) to image dimensions
        val u = (x / 1024f).coerceIn(0.0f, 1.0f)
        val v = (y / 1024f).coerceIn(0.0f, 1.0f)
        val px = (u * (w - 1)).toInt()
        val py = (v * (h - 1)).toInt()
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

    fun computeAverageCoveredColor(
        points: List<Point2D>,
        radii: List<Float>,
        defaultColor: Int = Color.BLACK
    ): Int {
        val b = bitmap ?: return defaultColor
        val w = b.width
        val h = b.height
        if (w <= 0 || h <= 0 || points.isEmpty()) return defaultColor

        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var count = 0L
        val visited = HashSet<Long>()

        for (i in points.indices) {
            val pt = points[i]
            val r = if (i < radii.size) radii[i] else (radii.lastOrNull() ?: 20f)

            val imgCenterX = (pt.x / 1024f) * w
            val imgCenterY = (pt.y / 1024f) * h
            val imgRadius = (r / 1024f) * max(w, h)

            val minX = max(0, (imgCenterX - imgRadius).toInt())
            val maxX = min(w - 1, (imgCenterX + imgRadius).toInt())
            val minY = max(0, (imgCenterY - imgRadius).toInt())
            val maxY = min(h - 1, (imgCenterY + imgRadius).toInt())

            val step = max(1, (imgRadius / 6f).toInt())
            var py = minY
            while (py <= maxY) {
                var px = minX
                while (px <= maxX) {
                    val dx = px - imgCenterX
                    val dy = py - imgCenterY
                    if (dx * dx + dy * dy <= imgRadius * imgRadius) {
                        val key = (px.toLong() shl 32) or (py.toLong() and 0xFFFFFFFFL)
                        if (visited.add(key)) {
                            val pixel = b.getPixel(px, py)
                            totalR += Color.red(pixel)
                            totalG += Color.green(pixel)
                            totalB += Color.blue(pixel)
                            count++
                        }
                    }
                    px += step
                }
                py += step
            }
        }

        if (count == 0L) {
            val first = points.first()
            return sampleColor(first.x, first.y, defaultColor)
        }

        val avgR = (totalR / count).toInt().coerceIn(0, 255)
        val avgG = (totalG / count).toInt().coerceIn(0, 255)
        val avgB = (totalB / count).toInt().coerceIn(0, 255)
        return Color.rgb(avgR, avgG, avgB)
    }

    fun release() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }
}
