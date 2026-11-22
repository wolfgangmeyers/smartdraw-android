package com.smartdraw.core

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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

        // Aspect ratio scaling & letterboxing mapping (SPECIFICATION.md §4.3.3 / §7)
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

    companion object {
        // 13-point disc stencil offsets: (dx_ratio, dy_ratio)
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
        defaultColor: Int = Color.BLACK
    ): Int {
        val bitmap = referenceBitmap ?: return defaultColor
        val rw = bitmap.width.toFloat()
        val rh = bitmap.height.toFloat()
        if (rw <= 0f || rh <= 0f || canvasWidth <= 0f || canvasHeight <= 0f || points.isEmpty()) return defaultColor

        val w = bitmap.width
        val h = bitmap.height

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
                    val pixel = bitmap.getPixel(px, py)
                    totalR += Color.red(pixel)
                    totalG += Color.green(pixel)
                    totalB += Color.blue(pixel)
                    count++
                }
            }

            idx += stride
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
}
