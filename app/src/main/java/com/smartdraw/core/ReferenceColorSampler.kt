package com.smartdraw.core

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0 || points.isEmpty()) return defaultColor

        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var count = 0L

        // Use a set or step sampling to avoid oversampling duplicate pixels
        val visited = HashSet<Long>()

        for (i in points.indices) {
            val pt = points[i]
            val r = if (i < radii.size) radii[i] else (radii.lastOrNull() ?: 20f)
            val rInt = max(1, r.toInt())

            val minX = max(0, (pt.x - r).toInt())
            val maxX = min(w - 1, (pt.x + r).toInt())
            val minY = max(0, (pt.y - r).toInt())
            val maxY = min(h - 1, (pt.y + r).toInt())

            // Sample within the circle
            val step = max(1, (r / 8f).toInt()) // Adaptive step for performance
            var py = minY
            while (py <= maxY) {
                var px = minX
                while (px <= maxX) {
                    val dx = px - pt.x
                    val dy = py - pt.y
                    if (dx * dx + dy * dy <= r * r) {
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
