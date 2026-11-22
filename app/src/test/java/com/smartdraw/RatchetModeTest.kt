package com.smartdraw

import android.graphics.Color
import com.smartdraw.core.BezierCurve
import com.smartdraw.core.Point2D
import com.smartdraw.core.RatchetEvaluator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RatchetModeTest {

    @Test
    fun testRatchetEvaluatorAcceptsImprovingStroke() {
        val width = 128
        val height = 128

        // Target reference: all white
        val refPixels = IntArray(width * height) { Color.WHITE }

        // Initial base canvas: all black
        val basePixels = IntArray(width * height) { Color.BLACK }

        // Candidate stroke across center: white stroke
        val p0 = Point2D(20f, 64f, 1.0f)
        val p1 = Point2D(64f, 64f, 1.0f)
        val p2 = Point2D(108f, 64f, 1.0f)
        val curve = BezierCurve.Quadratic(p0, p1, p2)
        val radius = 16f
        val opacity = 1.0f

        val box = RatchetEvaluator.computeBoundingBox(
            points = listOf(p0, p1, p2),
            radii = listOf(radius, radius, radius),
            maxSegmentRadius = radius,
            canvasWidth = width,
            canvasHeight = height
        )

        // Read sub-rect from basePixels
        val baseSubRect = IntArray(box.width * box.height)
        for (r in 0 until box.height) {
            val srcOffset = (box.y0 + r) * width + box.x0
            System.arraycopy(basePixels, srcOffset, baseSubRect, r * box.width, box.width)
        }

        val candidateSubRect = baseSubRect.copyOf()
        RatchetEvaluator.simulateStrokeOnSubRect(
            subRectPixels = candidateSubRect,
            box = box,
            segments = listOf(Triple(curve, radius, opacity)),
            finalColor = Color.WHITE
        )

        val accepted = RatchetEvaluator.evaluateSimilarity(
            basePixels = baseSubRect,
            candidatePixels = candidateSubRect,
            box = box,
            refPixels = refPixels,
            refWidth = width,
            refHeight = height,
            canvasWidth = width,
            canvasHeight = height
        )

        assertTrue("White stroke over black canvas with white reference must be accepted", accepted)
    }

    @Test
    fun testRatchetEvaluatorRejectsWorseningStroke() {
        val width = 128
        val height = 128

        // Target reference: all white
        val refPixels = IntArray(width * height) { Color.WHITE }

        // Initial base canvas: already all white
        val basePixels = IntArray(width * height) { Color.WHITE }

        // Candidate stroke across center: black stroke over white canvas
        val p0 = Point2D(20f, 64f, 1.0f)
        val p1 = Point2D(64f, 64f, 1.0f)
        val p2 = Point2D(108f, 64f, 1.0f)
        val curve = BezierCurve.Quadratic(p0, p1, p2)
        val radius = 16f
        val opacity = 1.0f

        val box = RatchetEvaluator.computeBoundingBox(
            points = listOf(p0, p1, p2),
            radii = listOf(radius, radius, radius),
            maxSegmentRadius = radius,
            canvasWidth = width,
            canvasHeight = height
        )

        val baseSubRect = IntArray(box.width * box.height)
        for (r in 0 until box.height) {
            val srcOffset = (box.y0 + r) * width + box.x0
            System.arraycopy(basePixels, srcOffset, baseSubRect, r * box.width, box.width)
        }

        val candidateSubRect = baseSubRect.copyOf()
        RatchetEvaluator.simulateStrokeOnSubRect(
            subRectPixels = candidateSubRect,
            box = box,
            segments = listOf(Triple(curve, radius, opacity)),
            finalColor = Color.BLACK
        )

        val accepted = RatchetEvaluator.evaluateSimilarity(
            basePixels = baseSubRect,
            candidatePixels = candidateSubRect,
            box = box,
            refPixels = refPixels,
            refWidth = width,
            refHeight = height,
            canvasWidth = width,
            canvasHeight = height
        )

        assertFalse("Black stroke over white canvas with white reference must be rejected", accepted)
    }

    @Test
    fun testSmartDrawViewRatchetToggle() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = SmartDrawView(context)

        assertFalse(view.isRatchetEnabled)
        view.setRatchetEnabled(true)
        assertTrue(view.isRatchetEnabled)
        view.setRatchetEnabled(false)
        assertFalse(view.isRatchetEnabled)
    }
}
