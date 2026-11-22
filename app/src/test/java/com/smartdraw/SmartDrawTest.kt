package com.smartdraw

import android.graphics.Bitmap
import android.graphics.Color
import android.view.MotionEvent
import com.smartdraw.core.BezierCurve
import com.smartdraw.core.Point2D
import com.smartdraw.core.ReferenceColorSampler
import com.smartdraw.gles.ReferenceLayer
import com.smartdraw.input.TouchInputHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartDrawTest {

    @Test
    fun testViewportAspectFitTallScreen() {
        // Typical phone: 1080 x 2100, canvas 1024 x 1024
        val canvasWidth = 1024
        val canvasHeight = 1024
        val viewWidth = 1080
        val viewHeight = 2100

        val canvasAspect = canvasWidth.toFloat() / canvasHeight.toFloat() // 1.0
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()       // ~0.514

        val vpWidth = viewWidth
        val vpHeight = (viewWidth / canvasAspect).toInt()
        val vpLeft = 0
        val vpTop = (viewHeight - vpHeight) / 2

        assertEquals(1080, vpWidth)
        assertEquals(1080, vpHeight)
        assertEquals(0, vpLeft)
        assertEquals(510, vpTop)
        val vpBottom = vpTop + vpHeight
        assertEquals(1590, vpBottom)

        // GL viewport y is: viewHeight - vpBottom
        val glViewportY = viewHeight - vpBottom
        assertEquals(510, glViewportY)

        // Coordinate transforms
        fun viewToCanvas(tx: Float, ty: Float): Pair<Float, Float> {
            val cx = (tx - vpLeft) * (canvasWidth.toFloat() / vpWidth.toFloat())
            val cy = (ty - vpTop) * (canvasHeight.toFloat() / vpHeight.toFloat())
            return Pair(cx, cy)
        }

        val topLeft = viewToCanvas(0f, 510f)
        assertEquals(0f, topLeft.first, 0.001f)
        assertEquals(0f, topLeft.second, 0.001f)

        val bottomRight = viewToCanvas(1080f, 1590f)
        assertEquals(1024f, bottomRight.first, 0.001f)
        assertEquals(1024f, bottomRight.second, 0.001f)

        val center = viewToCanvas(540f, 1050f)
        assertEquals(512f, center.first, 0.001f)
        assertEquals(512f, center.second, 0.001f)
    }

    @Test
    fun testViewportAspectFitWideScreen() {
        // Landscape or tablet: 2100 x 1080, canvas 1024 x 1024
        val canvasWidth = 1024
        val canvasHeight = 1024
        val viewWidth = 2100
        val viewHeight = 1080

        val canvasAspect = canvasWidth.toFloat() / canvasHeight.toFloat()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

        val vpHeight = viewHeight
        val vpWidth = (viewHeight * canvasAspect).toInt()
        val vpLeft = (viewWidth - vpWidth) / 2
        val vpTop = 0

        assertEquals(1080, vpWidth)
        assertEquals(1080, vpHeight)
        assertEquals(510, vpLeft)
        assertEquals(0, vpTop)

        fun viewToCanvas(tx: Float, ty: Float): Pair<Float, Float> {
            val cx = (tx - vpLeft) * (canvasWidth.toFloat() / vpWidth.toFloat())
            val cy = (ty - vpTop) * (canvasHeight.toFloat() / vpHeight.toFloat())
            return Pair(cx, cy)
        }

        val topLeft = viewToCanvas(510f, 0f)
        assertEquals(0f, topLeft.first, 0.001f)
        assertEquals(0f, topLeft.second, 0.001f)

        val bottomRight = viewToCanvas(1590f, 1080f)
        assertEquals(1024f, bottomRight.first, 0.001f)
        assertEquals(1024f, bottomRight.second, 0.001f)
    }

    @Test
    fun testTouchInputHandlerCoordinateTransformation() {
        val pointsSeen = mutableListOf<Point2D>()
        var strokeStarted = false
        var strokeEnded = false

        // Transformation adds 100 to x and 200 to y
        val handler = TouchInputHandler(
            baseRadius = 20f,
            transformPoint = { x, y -> Pair(x + 100f, y + 200f) },
            onStrokeStart = { pt ->
                strokeStarted = true
                pointsSeen.add(pt)
            },
            onStrokeSegment = { curve, _, _ ->
                pointsSeen.add(curve.start)
                pointsSeen.add(curve.end)
            },
            onStrokeEnd = {
                strokeEnded = true
            }
        )

        val pp = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        }
        val pcDown = MotionEvent.PointerCoords().apply {
            x = 50f
            y = 60f
            pressure = 0.5f
            size = 1f
        }
        val downEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 1, arrayOf(pp), arrayOf(pcDown), 0, 0, 1f, 1f, 0, 0, 0, 0)
        handler.handleTouchEvent(downEvent)
        downEvent.recycle()

        assertTrue(strokeStarted)
        assertEquals(1, pointsSeen.size)
        assertEquals(150f, pointsSeen[0].x, 0.001f)
        assertEquals(260f, pointsSeen[0].y, 0.001f)

        val pcUp = MotionEvent.PointerCoords().apply {
            x = 50f
            y = 60f
            pressure = 0.5f
            size = 1f
        }
        val upEvent = MotionEvent.obtain(0L, 100L, MotionEvent.ACTION_UP, 1, arrayOf(pp), arrayOf(pcUp), 0, 0, 1f, 1f, 0, 0, 0, 0)
        handler.handleTouchEvent(upEvent)
        upEvent.recycle()

        assertTrue(strokeEnded)
    }

    @Test
    fun testReferenceColorAverageCalculation() {
        // Create 100x100 bitmap: left half pure red (255, 0, 0), right half pure blue (0, 0, 255)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) {
            for (x in 0 until 50) {
                bitmap.setPixel(x, y, Color.RED)
            }
            for (x in 50 until 100) {
                bitmap.setPixel(x, y, Color.BLUE)
            }
        }

        val refLayer = ReferenceLayer()
        refLayer.setBitmap(bitmap, updateGlTexture = false)

        // 1. Stroke entirely in red region
        val redPoints = listOf(Point2D(200f, 512f), Point2D(250f, 512f))
        val redRadii = listOf(50f, 50f)
        val avgRed = refLayer.computeAverageCoveredColor(redPoints, redRadii)
        assertEquals(255, Color.red(avgRed))
        assertEquals(0, Color.green(avgRed))
        assertEquals(0, Color.blue(avgRed))

        // 2. Stroke spanning symmetrically across red and blue halves
        val spanPoints = listOf(Point2D(400f, 512f), Point2D(600f, 512f))
        val spanRadii = listOf(80f, 80f)
        val avgPurple = refLayer.computeAverageCoveredColor(spanPoints, spanRadii)
        // Red and Blue should be roughly equal and non-zero
        val r = Color.red(avgPurple)
        val b = Color.blue(avgPurple)
        assertTrue("Red should be significant: $r", r > 100)
        assertTrue("Blue should be significant: $b", b > 100)
        assertEquals("Green should be zero", 0, Color.green(avgPurple))
        assertEquals("Red and Blue should be approximately equal", r.toDouble(), b.toDouble(), 15.0)

        // 3. Empty points fallback
        val emptyAvg = refLayer.computeAverageCoveredColor(
            points = emptyList(),
            radii = emptyList(),
            defaultColor = Color.GREEN
        )
        assertEquals(Color.GREEN, emptyAvg)

        bitmap.recycle()
    }

    @Test
    fun testReferenceColorSamplerMatchesReferenceLayer() {
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.YELLOW)

        val sampler = ReferenceColorSampler(bitmap)
        val points = listOf(Point2D(25f, 25f))
        val radii = listOf(10f)

        val color = sampler.computeAverageCoveredColor(points, radii)
        assertEquals(255, Color.red(color))
        assertEquals(255, Color.green(color))
        assertEquals(0, Color.blue(color))

        bitmap.recycle()
    }
}
