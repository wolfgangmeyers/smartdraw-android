package com.smartdraw

import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartDrawViewTransformTest {

    private lateinit var view: SmartDrawView

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        view = SmartDrawView(context)
    }

    @Test
    fun testDefaultTransformIdentity() {
        // Translation is (0,0) by default before onSurfaceChanged, scale is 1.0, rotation is 0
        assertEquals(0f, view.viewportTranslationX, 0.001f)
        assertEquals(0f, view.viewportTranslationY, 0.001f)
        assertEquals(1.0f, view.viewportScale, 0.001f)
        assertEquals(0f, view.viewportRotationDegrees, 0.001f)

        val (cx, cy) = view.screenToCanvas(100f, 200f)
        assertEquals(100f, cx, 0.001f)
        assertEquals(200f, cy, 0.001f)

        val (sx, sy) = view.canvasToScreen(100f, 200f)
        assertEquals(100f, sx, 0.001f)
        assertEquals(200f, sy, 0.001f)
    }

    @Test
    fun testScreenToCanvasAndBackWithRotationAndScale() {
        // Set an arbitrary affine transform
        // T = (150, 250), Scale = 2.0, Rotation = 90 degrees
        // Using reflection or simulated events: we can test screenToCanvas / canvasToScreen directly
        val transXField = SmartDrawView::class.java.getDeclaredField("viewportTranslationX")
        transXField.isAccessible = true
        transXField.set(view, 150f)

        val transYField = SmartDrawView::class.java.getDeclaredField("viewportTranslationY")
        transYField.isAccessible = true
        transYField.set(view, 250f)

        val scaleField = SmartDrawView::class.java.getDeclaredField("viewportScale")
        scaleField.isAccessible = true
        scaleField.set(view, 2.0f)

        val rotField = SmartDrawView::class.java.getDeclaredField("viewportRotationDegrees")
        rotField.isAccessible = true
        rotField.set(view, 90f)

        // For canvas point (10, 0):
        // Scaled: (20, 0)
        // Rotated 90 deg: (0, 20)
        // Translated: (150, 270)
        val (sx, sy) = view.canvasToScreen(10f, 0f)
        assertEquals(150f, sx, 0.01f)
        assertEquals(270f, sy, 0.01f)

        // Inverse mapping of (150, 270):
        val (cx, cy) = view.screenToCanvas(sx, sy)
        assertEquals(10f, cx, 0.01f)
        assertEquals(0f, cy, 0.01f)
    }

    @Test
    fun testResetViewRestoresAllTransformParameters() {
        val transXField = SmartDrawView::class.java.getDeclaredField("viewportTranslationX")
        transXField.isAccessible = true
        transXField.set(view, 300f)

        val scaleField = SmartDrawView::class.java.getDeclaredField("viewportScale")
        scaleField.isAccessible = true
        scaleField.set(view, 3.5f)

        val rotField = SmartDrawView::class.java.getDeclaredField("viewportRotationDegrees")
        rotField.isAccessible = true
        rotField.set(view, 45f)

        view.resetView()

        assertEquals(1.0f, view.viewportScale, 0.001f)
        assertEquals(0f, view.viewportRotationDegrees, 0.001f)
    }

    @Test
    fun testSetCanvasDimensionsUpdatesDimensionsAndFitsViewport() {
        // Set dynamic dimensions (e.g., 2000 x 3000)
        view.setCanvasDimensions(2000, 3000)
        assertEquals(2000, view.canvasWidth)
        assertEquals(3000, view.canvasHeight)

        // Surface is default 1024 x 1024
        // fitScale = min(1.0, min(1024/2000, 1024/3000)) = 1024 / 3000 ≈ 0.34133
        val expectedScale = 1024f / 3000f
        assertEquals(expectedScale, view.viewportScale, 0.001f)
        assertEquals(0f, view.viewportRotationDegrees, 0.001f)
    }
}
