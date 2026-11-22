package com.smartdraw.loop

import android.graphics.Bitmap
import android.graphics.Color
import com.smartdraw.core.BezierCurve
import com.smartdraw.core.Point2D
import com.smartdraw.gles.SmartDrawRenderer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class ImprovementStats(
    val initialLoss: Double,
    val finalLoss: Double,
    val iterations: Int,
    val acceptedCount: Int,
    val rejectedCount: Int
) {
    val improvementPercent: Double
        get() = if (initialLoss > 0) ((initialLoss - finalLoss) / initialLoss) * 100.0 else 0.0
}

data class CandidateStroke(
    val curve: BezierCurve,
    val radius: Float,
    val color: Int,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int
)

class SteadyImprovementLoop(
    private val renderer: SmartDrawRenderer,
    private val referenceBitmap: Bitmap,
    private val random: Random = Random(42)
) {
    private val width = renderer.width
    private val height = renderer.height

    // Working CPU pixel buffers for fast local diff evaluation
    private val refPixels = IntArray(width * height)
    private val currentPixels = IntArray(width * height)
    private val scratchPixels = IntArray(width * height)

    init {
        referenceBitmap.getPixels(refPixels, 0, width, 0, 0, width, height)
        // Initialize current canvas with white background
        val white = Color.WHITE
        currentPixels.fill(white)
        System.arraycopy(currentPixels, 0, scratchPixels, 0, currentPixels.size)
    }

    fun computeGlobalLoss(): Double {
        var total = 0.0
        for (i in 0 until width * height) {
            val c = currentPixels[i]
            val r = refPixels[i]
            val dr = abs(((c shr 16) and 0xFF) - ((r shr 16) and 0xFF))
            val dg = abs(((c shr 8) and 0xFF) - ((r shr 8) and 0xFF))
            val db = abs((c and 0xFF) - (r and 0xFF))
            total += (dr + dg + db)
        }
        return total / (width * height * 3.0 * 255.0)
    }

    private fun computeBoxLoss(pixels: IntArray, minX: Int, minY: Int, maxX: Int, maxY: Int): Double {
        var total = 0.0
        var count = 0
        for (y in minY..maxY) {
            val row = y * width
            for (x in minX..maxX) {
                val c = pixels[row + x]
                val r = refPixels[row + x]
                val dr = abs(((c shr 16) and 0xFF) - ((r shr 16) and 0xFF))
                val dg = abs(((c shr 8) and 0xFF) - ((r shr 8) and 0xFF))
                val db = abs((c and 0xFF) - (r and 0xFF))
                total += (dr + dg + db)
                count++
            }
        }
        return if (count > 0) total else 0.0
    }

    fun generateCandidateStroke(iteration: Int, maxIterations: Int): CandidateStroke {
        // Dynamic brush radius: large coarse strokes early on, finer detailed strokes later
        val progress = iteration.toFloat() / maxIterations.coerceAtLeast(1)
        val maxRadius = (width * 0.12f) * (1.0f - progress * 0.75f)
        val minRadius = max(4.0f, (width * 0.02f) * (1.0f - progress * 0.7f))
        val radius = random.nextFloat() * (maxRadius - minRadius) + minRadius

        // Pick start point
        val startX = random.nextFloat() * (width - 1)
        val startY = random.nextFloat() * (height - 1)

        // Stroke length decreases as iterations proceed
        val maxLen = (width * 0.35f) * (1.0f - progress * 0.6f)
        val angle1 = random.nextFloat() * (2.0f * Math.PI.toFloat())
        val len1 = random.nextFloat() * maxLen + 10f

        val ctrlX = (startX + kotlin.math.cos(angle1) * (len1 * 0.5f)).coerceIn(0f, width - 1f)
        val ctrlY = (startY + kotlin.math.sin(angle1) * (len1 * 0.5f)).coerceIn(0f, height - 1f)

        val angle2 = angle1 + (random.nextFloat() - 0.5f) * 1.5f
        val endX = (ctrlX + kotlin.math.cos(angle2) * (len1 * 0.5f)).coerceIn(0f, width - 1f)
        val endY = (ctrlY + kotlin.math.sin(angle2) * (len1 * 0.5f)).coerceIn(0f, height - 1f)

        val p0 = Point2D(startX, startY, random.nextFloat() * 0.4f + 0.6f)
        val p1 = Point2D(ctrlX, ctrlY, random.nextFloat() * 0.4f + 0.6f)
        val p2 = Point2D(endX, endY, random.nextFloat() * 0.4f + 0.6f)

        val curve = BezierCurve.Quadratic(p0, p1, p2)

        // Sample color at midpoint from reference image
        val midPt = curve.evaluate(0.5f)
        val px = midPt.x.toInt().coerceIn(0, width - 1)
        val py = midPt.y.toInt().coerceIn(0, height - 1)
        val color = refPixels[py * width + px]

        // Calculate bounding box
        val pad = radius.toInt() + 4
        val bMinX = max(0, minOf(startX, ctrlX, endX).toInt() - pad)
        val bMinY = max(0, minOf(startY, ctrlY, endY).toInt() - pad)
        val bMaxX = min(width - 1, maxOf(startX, ctrlX, endX).toInt() + pad)
        val bMaxY = min(height - 1, maxOf(startY, ctrlY, endY).toInt() + pad)

        return CandidateStroke(curve, radius, color, bMinX, bMinY, bMaxX, bMaxY)
    }

    private fun rasterizeStrokeToScratch(stroke: CandidateStroke) {
        // Fast CPU software stamp for candidate evaluation
        val step = max(1.0f, stroke.radius * 0.25f)
        val points = stroke.curve.sampleUniform(step)

        val r = (stroke.color shr 16) and 0xFF
        val g = (stroke.color shr 8) and 0xFF
        val b = stroke.color and 0xFF

        for (pt in points) {
            val effRadius = stroke.radius * pt.pressure
            val rSq = effRadius * effRadius
            val xMin = max(stroke.minX, (pt.x - effRadius).toInt())
            val xMax = min(stroke.maxX, (pt.x + effRadius).toInt())
            val yMin = max(stroke.minY, (pt.y - effRadius).toInt())
            val yMax = min(stroke.maxY, (pt.y + effRadius).toInt())

            for (py in yMin..yMax) {
                val dy = py - pt.y
                val dySq = dy * dy
                val row = py * width
                for (px in xMin..xMax) {
                    val dx = px - pt.x
                    val dSq = dx * dx + dySq
                    if (dSq <= rSq) {
                        val dist = kotlin.math.sqrt(dSq)
                        val alpha = (1.0f - (dist - (effRadius - 1.0f)).coerceIn(0.0f, 2.0f) * 0.5f).coerceIn(0f, 1f)
                        if (alpha > 0.01f) {
                            val curr = scratchPixels[row + px]
                            val cr = (curr shr 16) and 0xFF
                            val cg = (curr shr 8) and 0xFF
                            val cb = curr and 0xFF

                            val nr = (r * alpha + cr * (1f - alpha)).toInt().coerceIn(0, 255)
                            val ng = (g * alpha + cg * (1f - alpha)).toInt().coerceIn(0, 255)
                            val nb = (b * alpha + cb * (1f - alpha)).toInt().coerceIn(0, 255)

                            scratchPixels[row + px] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                        }
                    }
                }
            }
        }
    }

    fun run(
        iterations: Int = 240,
        targetAccepted: Int = 180,
        onProgress: ((step: Int, accepted: Boolean, stats: ImprovementStats) -> Unit)? = null
    ): ImprovementStats {
        val initialLoss = computeGlobalLoss()
        var accepted = 0
        var rejected = 0
        var i = 0

        // Initialize renderer base canvas to white
        renderer.clear(1.0f, 1.0f, 1.0f, 1.0f)

        while (i < iterations && accepted < targetAccepted) {
            val candidate = generateCandidateStroke(i, iterations)

            // Copy bounding box from current to scratch
            for (y in candidate.minY..candidate.maxY) {
                val off = y * width + candidate.minX
                val len = candidate.maxX - candidate.minX + 1
                System.arraycopy(currentPixels, off, scratchPixels, off, len)
            }

            val lossBefore = computeBoxLoss(currentPixels, candidate.minX, candidate.minY, candidate.maxX, candidate.maxY)

            // Render candidate onto scratch
            rasterizeStrokeToScratch(candidate)

            val lossAfter = computeBoxLoss(scratchPixels, candidate.minX, candidate.minY, candidate.maxX, candidate.maxY)

            // If loss decreased, accept!
            if (lossAfter < lossBefore) {
                accepted++
                // Commit to currentPixels
                for (y in candidate.minY..candidate.maxY) {
                    val off = y * width + candidate.minX
                    val len = candidate.maxX - candidate.minX + 1
                    System.arraycopy(scratchPixels, off, currentPixels, off, len)
                }

                // Render into GLES renderer with shader pipeline
                renderer.drawBezierStroke(
                    curve = candidate.curve,
                    baseRadius = candidate.radius,
                    defaultColor = candidate.color,
                    useReferenceColors = false,
                    spacingRatio = 0.25f
                )

                onProgress?.invoke(i, true, ImprovementStats(initialLoss, computeGlobalLoss(), i + 1, accepted, rejected))
            } else {
                rejected++
                onProgress?.invoke(i, false, ImprovementStats(initialLoss, computeGlobalLoss(), i + 1, accepted, rejected))
            }
            i++
        }

        val finalLoss = computeGlobalLoss()
        return ImprovementStats(initialLoss, finalLoss, i, accepted, rejected)
    }
}
