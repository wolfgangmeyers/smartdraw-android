package com.smartdraw.harness

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.smartdraw.core.BezierCurve
import com.smartdraw.core.Point2D
import com.smartdraw.gles.EglContextManager
import com.smartdraw.gles.SmartDrawRenderer
import com.smartdraw.loop.SteadyImprovementLoop
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object BezierTestHarness {
    private const val TAG = "SmartDraw-Harness"
    const val CANVAS_SIZE = 1024

    data class HarnessReport(
        val generatedFiles: List<File>,
        val steadyImprovementInitialLoss: Double,
        val steadyImprovementFinalLoss: Double,
        val steadyImprovementAccepted: Int,
        val steadyImprovementIterations: Int
    )

    fun saveBitmapToPng(bitmap: Bitmap, destFile: File) {
        destFile.parentFile?.mkdirs()
        FileOutputStream(destFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
        Log.i(TAG, "Saved PNG: ${destFile.absolutePath} (${bitmap.width}x${bitmap.height})")
    }

    fun runAll(outputDir: File): HarnessReport {
        outputDir.mkdirs()
        val generatedFiles = mutableListOf<File>()

        // 1. Export Reference Images
        val radialRef = ReferenceImageGenerator.createRadialGradient(CANVAS_SIZE, CANVAS_SIZE)
        val geomRef = ReferenceImageGenerator.createGeometricAbstract(CANVAS_SIZE, CANVAS_SIZE)
        val sunsetRef = ReferenceImageGenerator.createLandscapeSunset(CANVAS_SIZE, CANVAS_SIZE)

        val radialRefFile = File(outputDir, "reference_radial_gradient.png")
        saveBitmapToPng(radialRef, radialRefFile)
        generatedFiles.add(radialRefFile)

        val geomRefFile = File(outputDir, "reference_geometric_abstract.png")
        saveBitmapToPng(geomRef, geomRefFile)
        generatedFiles.add(geomRefFile)

        val sunsetRefFile = File(outputDir, "reference_landscape_sunset.png")
        saveBitmapToPng(sunsetRef, sunsetRefFile)
        generatedFiles.add(sunsetRefFile)

        // Initialize EGL headless context for GLES 3.0 rendering
        val egl = EglContextManager(CANVAS_SIZE, CANVAS_SIZE)
        val renderer = SmartDrawRenderer(CANVAS_SIZE, CANVAS_SIZE)

        // 2. Test Painting 1: Bezier Curves Showcase (Varied curves, pressures, radii, auto color picking, 82 strokes)
        val showcaseFile = File(outputDir, "painting_bezier_pressure_50.png")
        val showcaseFileAlias = File(outputDir, "test_bezier_curves_showcase.png")
        runBezierCurvesShowcase(renderer, radialRef)
        val showcaseBmp = renderer.exportBaseBitmap()
        saveBitmapToPng(showcaseBmp, showcaseFile)
        saveBitmapToPng(showcaseBmp, showcaseFileAlias)
        generatedFiles.add(showcaseFile)
        generatedFiles.add(showcaseFileAlias)
        showcaseBmp.recycle()

        // 3. Stylus Dynamics & Calligraphy Test (52 strokes)
        val stylusFile = File(outputDir, "test_stylus_pressure_dynamics.png")
        runStylusDynamicsTest(renderer, geomRef)
        val stylusBmp = renderer.exportBaseBitmap()
        saveBitmapToPng(stylusBmp, stylusFile)
        generatedFiles.add(stylusFile)
        stylusBmp.recycle()

        // 4. Test Painting 2: GLES Smudge Shader Blend Test
        val smudgeFile = File(outputDir, "painting_smudge_test.png")
        val smudgeFileAlias = File(outputDir, "test_smudge_shader_blend.png")
        runSmudgeShaderTest(renderer)
        val smudgeBmp = renderer.exportBaseBitmap()
        saveBitmapToPng(smudgeBmp, smudgeFile)
        saveBitmapToPng(smudgeBmp, smudgeFileAlias)
        generatedFiles.add(smudgeFile)
        generatedFiles.add(smudgeFileAlias)
        smudgeBmp.recycle()

        // 5. Test Painting 3: Steady Improvement Loop (Dense artistic painting with 150-200 accepted strokes)
        val steadyFile = File(outputDir, "painting_dense_200.png")
        val steadyFileAlias = File(outputDir, "test_steady_improvement_painting.png")
        val loop = SteadyImprovementLoop(renderer, sunsetRef)
        val stats = loop.run(iterations = 250, targetAccepted = 180)
        Log.i(TAG, "Steady Improvement: ${stats.acceptedCount}/${stats.iterations} accepted. Initial loss: ${stats.initialLoss}, Final loss: ${stats.finalLoss}, Improvement: ${stats.improvementPercent}%")
        val steadyBmp = renderer.exportBaseBitmap()
        saveBitmapToPng(steadyBmp, steadyFile)
        saveBitmapToPng(steadyBmp, steadyFileAlias)
        generatedFiles.add(steadyFile)
        generatedFiles.add(steadyFileAlias)
        steadyBmp.recycle()

        // 6. Test Painting 5: Multi-layer Compositing Shader Test (Base layer + Reference layer at 0.35 opacity)
        val compositeFile = File(outputDir, "test_composited_layer_view.png")
        renderer.referenceLayer.setBitmap(sunsetRef)
        renderer.referenceLayer.opacity = 0.35f
        renderer.referenceLayer.isVisible = true
        val compositeBmp = renderer.exportCompositeBitmap()
        saveBitmapToPng(compositeBmp, compositeFile)
        generatedFiles.add(compositeFile)
        compositeBmp.recycle()

        // Cleanup
        renderer.release()
        egl.release()
        radialRef.recycle()
        geomRef.recycle()
        sunsetRef.recycle()

        return HarnessReport(
            generatedFiles = generatedFiles,
            steadyImprovementInitialLoss = stats.initialLoss,
            steadyImprovementFinalLoss = stats.finalLoss,
            steadyImprovementAccepted = stats.acceptedCount,
            steadyImprovementIterations = stats.iterations
        )
    }

    private fun runBezierCurvesShowcase(renderer: SmartDrawRenderer, reference: Bitmap) {
        renderer.clear(1.0f, 1.0f, 1.0f, 1.0f) // White background
        renderer.referenceLayer.setBitmap(reference)

        val cx = CANVAS_SIZE * 0.5f
        val cy = CANVAS_SIZE * 0.5f

        // --- 1. Multi-turn Spirals with Dynamic Pressure Ramps (0.1 to 1.0) ---
        // Generates cubic Bezier segments tracing logarithmic/archimedean spirals
        for (arm in 0 until 4) {
            val baseAngle = arm * (PI.toFloat() * 0.5f)
            var prevPt = Point2D(cx, cy, 0.1f)
            val segments = 12
            for (s in 1..segments) {
                val t0 = (s - 1).toFloat() / segments
                val t1 = s.toFloat() / segments
                val r0 = t0 * (CANVAS_SIZE * 0.42f)
                val r1 = t1 * (CANVAS_SIZE * 0.42f)

                val a0 = baseAngle + t0 * (2.5f * PI.toFloat())
                val a1 = baseAngle + t1 * (2.5f * PI.toFloat())
                val aMid1 = baseAngle + (t0 * 0.67f + t1 * 0.33f) * (2.5f * PI.toFloat())
                val aMid2 = baseAngle + (t0 * 0.33f + t1 * 0.67f) * (2.5f * PI.toFloat())

                val p0 = prevPt
                val p1 = Point2D(cx + cos(aMid1) * (r0 * 0.67f + r1 * 0.33f), cy + sin(aMid1) * (r0 * 0.67f + r1 * 0.33f), 0.1f + t0 * 0.8f)
                val p2 = Point2D(cx + cos(aMid2) * (r0 * 0.33f + r1 * 0.67f), cy + sin(aMid2) * (r0 * 0.33f + r1 * 0.67f), 0.1f + (t0 * 0.5f + t1 * 0.5f) * 0.8f)
                val p3 = Point2D(cx + cos(a1) * r1, cy + sin(a1) * r1, 0.1f + t1 * 0.8f)

                val curve = BezierCurve.Cubic(p0, p1, p2, p3)
                val radius = 6.0f + t1 * 26.0f // Varied radii: fine to broad
                renderer.drawBezierStroke(
                    curve = curve,
                    baseRadius = radius,
                    useReferenceColors = true,
                    spacingRatio = 0.2f
                )
                prevPt = p3
            }
        }

        // --- 2. Graceful S-Curves across the canvas with tapering pressure ---
        for (i in 0 until 10) {
            val yOffset = 80f + i * 90f
            val p0 = Point2D(60f, yOffset, 0.15f)
            val p1 = Point2D(CANVAS_SIZE * 0.35f, yOffset - 120f, 0.95f) // Heavy peak pressure
            val p2 = Point2D(CANVAS_SIZE * 0.65f, yOffset + 120f, 0.85f)
            val p3 = Point2D(CANVAS_SIZE - 60f, yOffset, 0.15f) // Tapered tail

            val curve = BezierCurve.Cubic(p0, p1, p2, p3)
            val radius = 12.0f + (i % 4) * 6f
            renderer.drawBezierStroke(
                curve = curve,
                baseRadius = radius,
                useReferenceColors = true,
                spacingRatio = 0.2f
            )
        }

        // --- 3. Looping Figure-Eight and Petal Arcs ---
        for (loop in 0 until 8) {
            val theta = loop * (PI.toFloat() / 4.0f)
            val dist = CANVAS_SIZE * 0.32f
            val loopCenter = Point2D(cx + cos(theta) * dist, cy + sin(theta) * dist)

            // Closed loop cubic Bezier
            val l0 = loopCenter
            val l1 = Point2D(loopCenter.x + cos(theta + 1.2f) * 110f, loopCenter.y + sin(theta + 1.2f) * 110f, 0.3f)
            val l2 = Point2D(loopCenter.x + cos(theta - 1.2f) * 110f, loopCenter.y + sin(theta - 1.2f) * 110f, 0.9f)
            val l3 = loopCenter

            val curve = BezierCurve.Cubic(l0, l1, l2, l3)
            renderer.drawBezierStroke(
                curve = curve,
                baseRadius = 14.0f,
                useReferenceColors = true,
                spacingRatio = 0.2f
            )
        }

        // --- 4. Straight Dynamic Ray Lines with Pressure Ramps ---
        for (ray in 0 until 16) {
            val angle = ray * (2.0f * PI.toFloat() / 16.0f)
            val startDist = CANVAS_SIZE * 0.08f
            val endDist = CANVAS_SIZE * 0.46f

            val r0 = Point2D(cx + cos(angle) * startDist, cy + sin(angle) * startDist, 1.0f)
            val rMid = Point2D(cx + cos(angle) * ((startDist + endDist) * 0.5f), cy + sin(angle) * ((startDist + endDist) * 0.5f), 0.5f)
            val r1 = Point2D(cx + cos(angle) * endDist, cy + sin(angle) * endDist, 0.1f)

            val quad = BezierCurve.Quadratic(r0, rMid, r1)
            renderer.drawBezierStroke(
                curve = quad,
                baseRadius = 8.0f,
                useReferenceColors = true,
                spacingRatio = 0.25f
            )
        }
    }

    private fun runStylusDynamicsTest(renderer: SmartDrawRenderer, reference: Bitmap) {
        renderer.clear(0.98f, 0.98f, 0.96f, 1.0f)
        renderer.referenceLayer.setBitmap(reference)

        // Simulates realistic stylus gestures: fast flick strokes, pressure swells, calligraphic flourishes
        for (row in 0 until 8) {
            val startY = 120f + row * 110f
            for (col in 0 until 5) {
                val startX = 100f + col * 180f
                // Calligraphic arc with heavy down-stroke and delicate release
                val p0 = Point2D(startX, startY, 0.2f)
                val p1 = Point2D(startX + 40f, startY + 80f, 1.0f) // Pressure swell
                val p2 = Point2D(startX + 120f, startY + 40f, 0.6f)
                val p3 = Point2D(startX + 150f, startY - 20f, 0.1f) // Whispering exit

                val curve = BezierCurve.Cubic(p0, p1, p2, p3)
                renderer.drawBezierStroke(
                    curve = curve,
                    baseRadius = 18f,
                    useReferenceColors = true,
                    spacingRatio = 0.2f
                )
            }
        }

        // Horizontal speed-pressure strokes across lower half
        for (i in 0 until 12) {
            val y = 700f + i * 24f
            val p0 = Point2D(80f, y, 0.1f)
            val midX = CANVAS_SIZE * 0.5f + sin(i * 0.8f) * 150f
            val p1 = Point2D(midX, y + cos(i * 0.5f) * 30f, 0.9f)
            val p2 = Point2D(CANVAS_SIZE - 80f, y, 0.1f)

            val quad = BezierCurve.Quadratic(p0, p1, p2)
            renderer.drawBezierStroke(
                curve = quad,
                baseRadius = 10f,
                useReferenceColors = true,
                spacingRatio = 0.25f
            )
        }
    }

    private fun runSmudgeShaderTest(renderer: SmartDrawRenderer) {
        renderer.clear(1.0f, 1.0f, 1.0f, 1.0f)

        // 1. First, paint bold colored stripes on base canvas
        val colors = intArrayOf(
            Color.rgb(230, 40, 40),   // Red
            Color.rgb(240, 160, 20),  // Orange
            Color.rgb(250, 220, 30),  // Yellow
            Color.rgb(40, 180, 80),   // Green
            Color.rgb(30, 120, 230),  // Blue
            Color.rgb(150, 40, 210)   // Purple
        )

        for (i in colors.indices) {
            val y = 160f + i * 130f
            val c = colors[i]
            val r = Color.red(c) / 255f
            val g = Color.green(c) / 255f
            val b = Color.blue(c) / 255f
            renderer.drawLine(
                x1 = 80f, y1 = y,
                x2 = CANVAS_SIZE - 80f, y2 = y,
                radius = 35f,
                colorR = r, colorG = g, colorB = b, colorA = 1.0f
            )
        }

        // 2. Apply GLES 3.0 Smudge Shader along Bezier paths across stripes
        // Vertical S-curves smudging colors together
        for (x in 200 until CANVAS_SIZE - 150 step 150) {
            val p0 = Point2D(x.toFloat(), 100f)
            val p1 = Point2D(x.toFloat() - 100f, CANVAS_SIZE * 0.5f)
            val p2 = Point2D(x.toFloat() + 100f, CANVAS_SIZE * 0.75f)
            val p3 = Point2D(x.toFloat(), CANVAS_SIZE - 100f)

            val curve = BezierCurve.Cubic(p0, p1, p2, p3)
            renderer.smudgeBezier(
                curve = curve,
                radius = 32f,
                blurStrength = 3.5f,
                opacity = 0.75f,
                spacingRatio = 0.2f
            )
        }

        // Swirling smudge in the center
        val cx = CANVAS_SIZE * 0.5f
        val cy = CANVAS_SIZE * 0.5f
        var prevPt = Point2D(cx, cy)
        for (s in 1..8) {
            val t = s.toFloat() / 8
            val rad = t * 220f
            val a = t * 3.0f * PI.toFloat()
            val nextPt = Point2D(cx + cos(a) * rad, cy + sin(a) * rad)
            val quad = BezierCurve.Quadratic(prevPt, Point2D.lerp(prevPt, nextPt, 0.5f), nextPt)
            renderer.smudgeBezier(quad, radius = 40f, blurStrength = 4.0f, opacity = 0.8f)
            prevPt = nextPt
        }
    }
}
