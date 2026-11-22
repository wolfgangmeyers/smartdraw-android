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

    /**
     * Computes the average color of all reference image pixels covered by a series
     * of stamp circles along a stroke path.
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

        val scale = minOf(canvasWidth / rw, canvasHeight / rh)
        val drawnW = rw * scale
        val drawnH = rh * scale
        val offsetX = (canvasWidth - drawnW) * 0.5f
        val offsetY = (canvasHeight - drawnH) * 0.5f

        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var count = 0L

        // Use a set or step sampling to avoid oversampling duplicate pixels
        val visited = HashSet<Long>()

        val w = bitmap.width
        val h = bitmap.height

        for (i in points.indices) {
            val pt = points[i]
            val r = if (i < radii.size) radii[i] else (radii.lastOrNull() ?: 20f)

            // Convert center and radius from canvas space to image space
            val imgCenterX = (pt.x - offsetX) / scale
            val imgCenterY = (pt.y - offsetY) / scale
            val imgRadius = r / scale

            val minX = max(0, (imgCenterX - imgRadius).toInt())
            val maxX = min(w - 1, (imgCenterX + imgRadius).toInt())
            val minY = max(0, (imgCenterY - imgRadius).toInt())
            val maxY = min(h - 1, (imgCenterY + imgRadius).toInt())

            // Sample within the circle
            val step = max(1, (imgRadius / 8f).toInt()) // Adaptive step for performance
            var py = minY
            while (py <= maxY) {
                var px = minX
                while (px <= maxX) {
                    val dx = px - imgCenterX
                    val dy = py - imgCenterY
                    if (dx * dx + dy * dy <= imgRadius * imgRadius) {
                        val key = (px.toLong() shl 32) or (py.toLong() and 0xFFFFFFFFL)
                        if (visited.add(key)) {
                            val pixel = bitmap.getPixel(px, py)
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
            // Fallback: sample just the first point
            val first = points.first()
            return sampleColor(first.x, first.y, defaultColor)
        }

        val avgR = (totalR / count).toInt().coerceIn(0, 255)
        val avgG = (totalG / count).toInt().coerceIn(0, 255)
        val avgB = (totalB / count).toInt().coerceIn(0, 255)
        return Color.rgb(avgR, avgG, avgB)
    }
}
