package com.smartdraw.core

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Ratchet Mode (Distance-Monotonic Guided Painting) Evaluator.
 *
 * Enforces the Ratchet Invariant:
 * Every committed human or automated stroke must monotonically preserve or reduce the distance
 * between the canvas painting and the underlying reference image:
 *   Loss(P', I_ref) <= Loss(P, I_ref)
 * where P is the pristine base canvas, P' is the candidate canvas with the active stroke applied,
 * and I_ref is the reference image.
 *
 * To guarantee instant real-time performance without GPU stalls:
 * 1. The spatial bounding box Omega_S enclosing the stroke path + brush radius is determined.
 * 2. Only the sub-rectangle pixels are read from baseFbo.
 * 3. The candidate stroke is rasterized in software onto a local copy of the sub-rectangle.
 * 4. The L1 Manhattan color distance (sum of absolute RGB differences) against reference pixels
 *    is evaluated before and after the candidate stroke.
 * 5. If loss increases, the stroke is rolled back at zero GPU cost by discarding activeStrokeFbo.
 */
object RatchetEvaluator {

    data class BoundingBox(
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int
    ) {
        val width: Int get() = x1 - x0 + 1
        val height: Int get() = y1 - y0 + 1
    }

    /**
     * Compute canvas-clamped spatial bounding box Omega_S enclosing all active stroke points,
     * Bezier segments, and brush radii with antialiasing padding.
     */
    fun computeBoundingBox(
        points: List<Point2D>,
        radii: List<Float>,
        maxSegmentRadius: Float,
        canvasWidth: Int,
        canvasHeight: Int
    ): BoundingBox {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        var maxR = maxSegmentRadius

        for (i in points.indices) {
            val pt = points[i]
            val r = if (i < radii.size) radii[i] else 20f
            if (pt.x < minX) minX = pt.x
            if (pt.y < minY) minY = pt.y
            if (pt.x > maxX) maxX = pt.x
            if (pt.y > maxY) maxY = pt.y
            if (r > maxR) maxR = r
        }

        val pad = maxR + 2f
        val boxX0 = (minX - pad).toInt().coerceIn(0, canvasWidth - 1)
        val boxY0 = (minY - pad).toInt().coerceIn(0, canvasHeight - 1)
        val boxX1 = (maxX + pad).toInt().coerceIn(0, canvasWidth - 1)
        val boxY1 = (maxY + pad).toInt().coerceIn(0, canvasHeight - 1)

        return BoundingBox(boxX0, boxY0, boxX1, boxY1)
    }

    /**
     * Simulate rendering stroke segments onto sub-rect pixels using software disc stamps.
     * Evaluates antialiased circular disc stamps with pressure modulation, reproducing the
     * GLES CircleBrushShader alpha blending onto the candidate sub-rect buffer without GPU round-trips.
     */
    fun simulateStrokeOnSubRect(
        subRectPixels: IntArray,
        box: BoundingBox,
        segments: List<Triple<BezierCurve, Float, Float>>, // curve, radius, opacity
        finalColor: Int,
        spacingRatio: Float = 0.2f,
        toolModes: List<com.smartdraw.SmartDrawView.ToolMode>? = null
    ) {
        val strokeR = Color.red(finalColor)
        val strokeG = Color.green(finalColor)
        val strokeB = Color.blue(finalColor)
        val baseAlpha = Color.alpha(finalColor) / 255f
        val boxW = box.width
        val boxH = box.height

        for (i in segments.indices) {
            val (curve, radius, opacity) = segments[i]
            val mode = toolModes?.getOrNull(i) ?: com.smartdraw.SmartDrawView.ToolMode.PAINTBRUSH
            val stepSize = max(0.5f, radius * spacingRatio)
            val samples = curve.sampleUniform(stepSize)
            val segOpacity = baseAlpha * opacity

            for (pt in samples) {
                val effRadius = when (mode) {
                    com.smartdraw.SmartDrawView.ToolMode.PEN,
                    com.smartdraw.SmartDrawView.ToolMode.PENCIL -> radius
                    else -> radius * pt.pressure
                }
                val effOpacity = when (mode) {
                    com.smartdraw.SmartDrawView.ToolMode.PENCIL -> segOpacity * pt.pressure
                    else -> segOpacity
                }
                val rSq = effRadius * effRadius
                val stampMinX = max(box.x0, (pt.x - effRadius).toInt())
                val stampMaxX = min(box.x0 + boxW - 1, (pt.x + effRadius).toInt())
                val stampMinY = max(box.y0, (pt.y - effRadius).toInt())
                val stampMaxY = min(box.y0 + boxH - 1, (pt.y + effRadius).toInt())

                for (py in stampMinY..stampMaxY) {
                    val dy = py - pt.y
                    val dySq = dy * dy
                    val localY = py - box.y0
                    val rowOffset = localY * boxW

                    for (px in stampMinX..stampMaxX) {
                        val dx = px - pt.x
                        val dSq = dx * dx + dySq
                        if (dSq <= rSq) {
                            val dist = kotlin.math.sqrt(dSq)
                            val edgeDist = effRadius - dist
                            val antialias = edgeDist.coerceIn(0f, 1f)
                            val stampAlpha = (effOpacity * antialias).coerceIn(0f, 1f)

                            if (stampAlpha > 0.005f) {
                                val localX = px - box.x0
                                val idx = rowOffset + localX
                                val curr = subRectPixels[idx]
                                val cr = (curr shr 16) and 0xFF
                                val cg = (curr shr 8) and 0xFF
                                val cb = curr and 0xFF

                                val nr = (strokeR * stampAlpha + cr * (1f - stampAlpha)).toInt().coerceIn(0, 255)
                                val ng = (strokeG * stampAlpha + cg * (1f - stampAlpha)).toInt().coerceIn(0, 255)
                                val nb = (strokeB * stampAlpha + cb * (1f - stampAlpha)).toInt().coerceIn(0, 255)

                                subRectPixels[idx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Evaluates whether candidate pixels reduce or preserve color loss compared to base pixels
     * relative to reference image pixels over the bounding box Omega_S.
     *
     * Uses L1 Manhattan color channel distance:
     *   Loss = sum(|R - R_ref| + |G - G_ref| + |B - B_ref|)
     *
     * Returns true if lossAfter <= lossBefore (commit stroke to base canvas),
     * or false if lossAfter > lossBefore (retrograde stroke; discard activeStrokeFbo).
     */
    fun evaluateSimilarity(
        basePixels: IntArray,
        candidatePixels: IntArray,
        box: BoundingBox,
        refPixels: IntArray,
        refWidth: Int,
        refHeight: Int,
        canvasWidth: Int,
        canvasHeight: Int
    ): Boolean {
        var lossBefore = 0.0
        var lossAfter = 0.0
        val boxW = box.width
        val boxH = box.height

        for (row in 0 until boxH) {
            val canvasY = box.y0 + row
            val refY = ((canvasY.toFloat() / canvasHeight) * refHeight).toInt().coerceIn(0, refHeight - 1)
            val offset = row * boxW
            val refRowOffset = refY * refWidth

            for (col in 0 until boxW) {
                val canvasX = box.x0 + col
                val refX = ((canvasX.toFloat() / canvasWidth) * refWidth).toInt().coerceIn(0, refWidth - 1)
                val refPixel = refPixels[refRowOffset + refX]

                val refR = (refPixel shr 16) and 0xFF
                val refG = (refPixel shr 8) and 0xFF
                val refB = refPixel and 0xFF

                val basePixel = basePixels[offset + col]
                val baseR = (basePixel shr 16) and 0xFF
                val baseG = (basePixel shr 8) and 0xFF
                val baseB = basePixel and 0xFF

                val candPixel = candidatePixels[offset + col]
                val candR = (candPixel shr 16) and 0xFF
                val candG = (candPixel shr 8) and 0xFF
                val candB = candPixel and 0xFF

                val dBaseR = abs(baseR - refR)
                val dBaseG = abs(baseG - refG)
                val dBaseB = abs(baseB - refB)
                lossBefore += (dBaseR + dBaseG + dBaseB)

                val dCandR = abs(candR - refR)
                val dCandG = abs(candG - refG)
                val dCandB = abs(candB - refB)
                lossAfter += (dCandR + dCandG + dCandB)
            }
        }

        return lossAfter <= lossBefore
    }
}
