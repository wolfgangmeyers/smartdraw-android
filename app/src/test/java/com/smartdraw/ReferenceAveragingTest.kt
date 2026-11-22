package com.smartdraw

import android.graphics.Bitmap
import android.graphics.Color
import com.smartdraw.core.BezierCurve
import com.smartdraw.core.Point2D
import com.smartdraw.gles.ReferenceLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ReferenceAveragingTest {

    @Test
    fun testTwoColorAveragingAlongStroke() {
        // Create a 200x200 bitmap: top half pure red, bottom half pure blue
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        for (y in 0 until 200) {
            for (x in 0 until 200) {
                if (y < 100) {
                    bitmap.setPixel(x, y, Color.rgb(255, 0, 0)) // Pure Red
                } else {
                    bitmap.setPixel(x, y, Color.rgb(0, 0, 255)) // Pure Blue
                }
            }
        }

        val refLayer = ReferenceLayer()
        refLayer.setBitmap(bitmap, updateGlTexture = false)

        // Draw a vertical stroke from (512, 256) [top half: Red] to (512, 768) [bottom half: Blue]
        // across a 1024x1024 canvas with radius 30
        val p0 = Point2D(512f, 256f, 1.0f)
        val p1 = Point2D(512f, 768f, 1.0f)
        val curve = BezierCurve.Quadratic(p0, Point2D.lerp(p0, p1, 0.5f), p1)
        val samples = curve.sampleUniform(10f)
        val radii = List(samples.size) { 30f }

        val avgColor = refLayer.computeAverageCoveredColor(
            points = samples,
            radii = radii,
            canvasWidth = 1024f,
            canvasHeight = 1024f
        )

        val r = Color.red(avgColor)
        val g = Color.green(avgColor)
        val b = Color.blue(avgColor)

        // The stroke is symmetrical across the red/blue boundary
        // Expect Red ~ 127, Green == 0, Blue ~ 127
        assertEquals("Green should be 0", 0, g)
        assertTrue("Red should be around 127 (+/- 5), got $r", r in 120..135)
        assertTrue("Blue should be around 127 (+/- 5), got $b", b in 120..135)
    }

    @Test
    fun testSinglePointAveraging() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                bitmap.setPixel(x, y, Color.rgb(50, 150, 200))
            }
        }

        val refLayer = ReferenceLayer()
        refLayer.setBitmap(bitmap, updateGlTexture = false)

        val point = listOf(Point2D(512f, 512f))
        val avgColor = refLayer.computeAverageCoveredColor(
            points = point,
            radii = listOf(20f),
            canvasWidth = 1024f,
            canvasHeight = 1024f
        )

        assertEquals(50, Color.red(avgColor))
        assertEquals(150, Color.green(avgColor))
        assertEquals(200, Color.blue(avgColor))
    }
}
