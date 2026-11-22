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

    val hasImage: Boolean
        get() = bitmap != null && !(bitmap?.isRecycled ?: true)

    fun setBitmap(source: Bitmap, updateGlTexture: Boolean = true) {
        bitmap = source
        if (updateGlTexture) {
            updateTexture()
        }
    }

    fun updateTexture() {
        val src = bitmap ?: return
        if (src.isRecycled) return

        // Flip vertically for OpenGL texture coordinate convention
        val flipped = try {
            val matrix = Matrix().apply { postScale(1.0f, -1.0f) }
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        } catch (e: Exception) {
            val safe = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(safe)
            val matrix = Matrix().apply {
                postScale(1.0f, -1.0f)
                postTranslate(0f, src.height.toFloat())
            }
            canvas.drawBitmap(src, matrix, null)
            safe
        }

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
        defaultColor: Int = Color.BLACK,
        canvasWidth: Float = 1024f,
        canvasHeight: Float = 1024f
    ): Int {
        val b = bitmap ?: return defaultColor
        val w = b.width
        val h = b.height
        if (w <= 0 || h <= 0) return defaultColor

        val u = (canvasX / canvasWidth).coerceIn(0.0f, 1.0f)
        val v = (canvasY / canvasHeight).coerceIn(0.0f, 1.0f)
        val px = (u * (w - 1)).toInt()
        val py = (v * (h - 1)).toInt()
        return b.getPixel(px, py)
    }

    fun sampleColorNormalized(
        x: Float,
        y: Float,
        defaultColor: Int = Color.BLACK,
        canvasWidth: Float = 1024f,
        canvasHeight: Float = 1024f
    ): FloatArray {
        val color = sampleColor(x, y, defaultColor, canvasWidth, canvasHeight)
        val a = Color.alpha(color) / 255.0f
        val r = Color.red(color) / 255.0f
        val g = Color.green(color) / 255.0f
        val b = Color.blue(color) / 255.0f
        return floatArrayOf(r, g, b, a)
    }

    companion object {
        // 13-point disc stencil offsets: (dx_ratio, dy_ratio)
        // Center: (0, 0)
        // Inner ring at 0.3r (4 points): (0.3, 0), (-0.3, 0), (0, 0.3), (0, -0.3)
        // Outer ring at 0.6r (8 points): (0.6, 0), (-0.6, 0), (0, 0.6), (0, -0.6),
        // and 4 diagonals at 0.6 / sqrt(2) ≈ 0.4243
        private val DISC_OFFSETS = arrayOf(
            0.0f to 0.0f,
            0.3f to 0.0f,
            -0.3f to 0.0f,
            0.0f to 0.3f,
            0.0f to -0.3f,
            0.6f to 0.0f,
            -0.6f to 0.0f,
            0.0f to 0.6f,
            0.0f to -0.6f,
            0.4243f to 0.4243f,
            -0.4243f to 0.4243f,
            0.4243f to -0.4243f,
            -0.4243f to -0.4243f
        )
    }

    /**
     * Fast, high-quality stroke disc sampling along the stroke path.
     * Replaces the O(W x H x N) bounding box loop to eliminate any UI freeze.
     */
    fun computeAverageCoveredColor(
        points: List<Point2D>,
        radii: List<Float>,
        canvasWidth: Float = 1024f,
        canvasHeight: Float = 1024f,
        defaultColor: Int = Color.BLACK
    ): Int {
        val b = bitmap ?: return defaultColor
        val w = b.width
        val h = b.height
        if (w <= 0 || h <= 0 || points.isEmpty() || canvasWidth <= 0f || canvasHeight <= 0f) return defaultColor

        val maxPositions = 60
        val stride = max(1, points.size / maxPositions)

        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var count = 0L

        var idx = 0
        while (idx < points.size) {
            val pt = points[idx]
            val r = if (idx < radii.size) radii[idx] else (radii.lastOrNull() ?: 20f)

            for ((dxRatio, dyRatio) in DISC_OFFSETS) {
                val cx = pt.x + dxRatio * r
                val cy = pt.y + dyRatio * r

                val px = ((cx / canvasWidth) * w).toInt()
                val py = ((cy / canvasHeight) * h).toInt()

                if (px in 0 until w && py in 0 until h) {
                    val pixel = b.getPixel(px, py)
                    totalR += Color.red(pixel)
                    totalG += Color.green(pixel)
                    totalB += Color.blue(pixel)
                    count++
                }
            }

            idx += stride
        }

        if (count == 0L) {
            return sampleColor(points[0].x, points[0].y, defaultColor, canvasWidth, canvasHeight)
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
