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

    fun setBitmap(source: Bitmap, updateGlTexture: Boolean = true) {
        bitmap = source
        if (updateGlTexture) {
            updateTexture()
        }
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

    /**
     * Accurately computes the average color of all reference image pixels covered by
     * the continuous stroke path.
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
        if (w <= 0 || h <= 0 || points.isEmpty()) return defaultColor

        if (points.size == 1) {
            val p = points[0]
            val r = radii.firstOrNull() ?: 20f
            return sampleCircleAverage(b, p.x, p.y, r, canvasWidth, canvasHeight, defaultColor)
        }

        // Find bounding box of the entire stroke in canvas space
        var minCx = Float.MAX_VALUE
        var maxCx = -Float.MAX_VALUE
        var minCy = Float.MAX_VALUE
        var maxCy = -Float.MAX_VALUE
        var maxRadius = 0f

        for (i in points.indices) {
            val p = points[i]
            val r = if (i < radii.size) radii[i] else (radii.lastOrNull() ?: 20f)
            if (r > maxRadius) maxRadius = r
            if (p.x < minCx) minCx = p.x
            if (p.x > maxCx) maxCx = p.x
            if (p.y < minCy) minCy = p.y
            if (p.y > maxCy) maxCy = p.y
        }

        minCx -= maxRadius
        maxCx += maxRadius
        minCy -= maxRadius
        maxCy += maxRadius

        // Map to reference bitmap pixel coordinates
        val minPx = max(0, ((minCx / canvasWidth) * w).toInt())
        val maxPx = min(w - 1, ((maxCx / canvasWidth) * w).toInt())
        val minPy = max(0, ((minCy / canvasHeight) * h).toInt())
        val maxPy = min(h - 1, ((maxCy / canvasHeight) * h).toInt())

        if (minPx > maxPx || minPy > maxPy) {
            return sampleColor(points[0].x, points[0].y, defaultColor, canvasWidth, canvasHeight)
        }

        // Build line segments between consecutive stroke points
        val numSegs = points.size - 1
        val segX1 = FloatArray(numSegs)
        val segY1 = FloatArray(numSegs)
        val segX2 = FloatArray(numSegs)
        val segY2 = FloatArray(numSegs)
        val segRadSq = FloatArray(numSegs)

        for (i in 0 until numSegs) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val r1 = if (i < radii.size) radii[i] else maxRadius
            val r2 = if (i + 1 < radii.size) radii[i + 1] else maxRadius
            val avgR = (r1 + r2) * 0.5f
            segX1[i] = p1.x
            segY1[i] = p1.y
            segX2[i] = p2.x
            segY2[i] = p2.y
            segRadSq[i] = avgR * avgR
        }

        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var count = 0L

        // Iterate all bitmap pixels in the bounding box
        for (py in minPy..maxPy) {
            val cy = ((py + 0.5f) / h.toFloat()) * canvasHeight
            for (px in minPx..maxPx) {
                val cx = ((px + 0.5f) / w.toFloat()) * canvasWidth

                // Check if (cx, cy) is within radius of any segment
                var covered = false
                for (s in 0 until numSegs) {
                    val x1 = segX1[s]
                    val y1 = segY1[s]
                    val x2 = segX2[s]
                    val y2 = segY2[s]
                    val dx = x2 - x1
                    val dy = y2 - y1
                    val lenSq = dx * dx + dy * dy
                    val distSq: Float
                    if (lenSq < 1e-4f) {
                        val ex = cx - x1
                        val ey = cy - y1
                        distSq = ex * ex + ey * ey
                    } else {
                        val t = (((cx - x1) * dx + (cy - y1) * dy) / lenSq).coerceIn(0.0f, 1.0f)
                        val projX = x1 + t * dx
                        val projY = y1 + t * dy
                        val ex = cx - projX
                        val ey = cy - projY
                        distSq = ex * ex + ey * ey
                    }

                    if (distSq <= segRadSq[s]) {
                        covered = true
                        break
                    }
                }

                if (covered) {
                    val pixel = b.getPixel(px, py)
                    totalR += Color.red(pixel)
                    totalG += Color.green(pixel)
                    totalB += Color.blue(pixel)
                    count++
                }
            }
        }

        if (count == 0L) {
            return sampleColor(points[0].x, points[0].y, defaultColor, canvasWidth, canvasHeight)
        }

        val avgR = (totalR / count).toInt().coerceIn(0, 255)
        val avgG = (totalG / count).toInt().coerceIn(0, 255)
        val avgB = (totalB / count).toInt().coerceIn(0, 255)
        return Color.rgb(avgR, avgG, avgB)
    }

    private fun sampleCircleAverage(
        b: Bitmap,
        cx: Float,
        cy: Float,
        radius: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        defaultColor: Int
    ): Int {
        val w = b.width
        val h = b.height
        val imgCenterX = (cx / canvasWidth) * w
        val imgCenterY = (cy / canvasHeight) * h
        val imgRadiusX = (radius / canvasWidth) * w
        val imgRadiusY = (radius / canvasHeight) * h

        val minPx = max(0, (imgCenterX - imgRadiusX).toInt())
        val maxPx = min(w - 1, (imgCenterX + imgRadiusX).toInt())
        val minPy = max(0, (imgCenterY - imgRadiusY).toInt())
        val maxPy = min(h - 1, (imgCenterY + imgRadiusY).toInt())

        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var count = 0L

        for (py in minPy..maxPy) {
            val dy = (py - imgCenterY) / imgRadiusY
            for (px in minPx..maxPx) {
                val dx = (px - imgCenterX) / imgRadiusX
                if (dx * dx + dy * dy <= 1.0f) {
                    val pixel = b.getPixel(px, py)
                    totalR += Color.red(pixel)
                    totalG += Color.green(pixel)
                    totalB += Color.blue(pixel)
                    count++
                }
            }
        }

        if (count == 0L) return b.getPixel(imgCenterX.toInt().coerceIn(0, w - 1), imgCenterY.toInt().coerceIn(0, h - 1))
        return Color.rgb((totalR / count).toInt(), (totalG / count).toInt(), (totalB / count).toInt())
    }

    fun release() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }
}
