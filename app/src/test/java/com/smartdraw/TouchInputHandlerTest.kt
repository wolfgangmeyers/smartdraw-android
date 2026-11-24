package com.smartdraw

import android.view.MotionEvent
import com.smartdraw.core.BezierCurve
import com.smartdraw.input.TouchInputHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TouchInputHandlerTest {

    private fun createMotionEvent(
        action: Int,
        x: Float,
        y: Float,
        toolType: Int,
        pressure: Float = 0.5f,
        buttonState: Int = 0
    ): MotionEvent {
        val pp = MotionEvent.PointerProperties().apply {
            id = 0
            this.toolType = toolType
        }
        val pc = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = pressure
            size = 1f
        }
        val downTime = 1000L
        val eventTime = 1000L
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            arrayOf(pp),
            arrayOf(pc),
            0,
            buttonState,
            1f,
            1f,
            0,
            0,
            0,
            0
        )
    }

    @Test
    fun testFingerTouchNeverEmitsStrokes() {
        val emittedCurves = mutableListOf<BezierCurve>()
        val handler = TouchInputHandler(
            onStrokeSegment = { curve, _, _ ->
                emittedCurves.add(curve)
            }
        )

        val downEvent = createMotionEvent(MotionEvent.ACTION_DOWN, 100f, 100f, MotionEvent.TOOL_TYPE_FINGER)
        val handledDown = handler.handleTouchEvent(downEvent)
        assertFalse("Finger touch down should not be handled for drawing", handledDown)
        assertFalse("Stylus should not be active on finger touch", handler.isStylusActive)

        val moveEvent = createMotionEvent(MotionEvent.ACTION_MOVE, 150f, 150f, MotionEvent.TOOL_TYPE_FINGER)
        val handledMove = handler.handleTouchEvent(moveEvent)
        assertFalse("Finger touch move should not be handled for drawing", handledMove)

        val upEvent = createMotionEvent(MotionEvent.ACTION_UP, 200f, 200f, MotionEvent.TOOL_TYPE_FINGER)
        val handledUp = handler.handleTouchEvent(upEvent)
        assertFalse("Finger touch up should not be handled for drawing", handledUp)

        assertEquals("No strokes should ever be emitted for finger touches", 0, emittedCurves.size)
    }

    @Test
    fun testStylusTouchEmitsStrokesWithCoordinateTransformation() {
        val emittedCurves = mutableListOf<BezierCurve>()
        var strokeEnded = false

        val handler = TouchInputHandler(
            screenToCanvasTransformer = { sx, sy ->
                // Example: pan = (50, 50), scale = 2.0 -> canvas = (sx - 50) / 2
                Pair((sx - 50f) / 2f, (sy - 50f) / 2f)
            },
            onStrokeSegment = { curve, _, _ ->
                emittedCurves.add(curve)
            },
            onStrokeEnd = {
                strokeEnded = true
            }
        )

        val downEvent = createMotionEvent(MotionEvent.ACTION_DOWN, 150f, 150f, MotionEvent.TOOL_TYPE_STYLUS, 0.6f)
        val handledDown = handler.handleTouchEvent(downEvent)
        assertTrue("Stylus touch down should be handled", handledDown)
        assertTrue("Stylus should be marked active", handler.isStylusActive)

        // Screen 250, 250 -> Canvas (250 - 50) / 2 = 100
        val moveEvent = createMotionEvent(MotionEvent.ACTION_MOVE, 250f, 250f, MotionEvent.TOOL_TYPE_STYLUS, 0.7f)
        val handledMove = handler.handleTouchEvent(moveEvent)
        assertTrue("Stylus move should be handled", handledMove)

        val upEvent = createMotionEvent(MotionEvent.ACTION_UP, 350f, 350f, MotionEvent.TOOL_TYPE_STYLUS, 0.8f)
        val handledUp = handler.handleTouchEvent(upEvent)
        assertTrue("Stylus up should be handled", handledUp)
        assertFalse("Stylus should be inactive after up", handler.isStylusActive)
        assertTrue("Stroke end callback should have fired", strokeEnded)

        assertTrue("Should have emitted at least one curve segment", emittedCurves.isNotEmpty())
        // Verify coordinates in canvas space: (150 - 50) / 2 = 50
        assertEquals(50f, emittedCurves.first().start.x, 0.01f)
        assertEquals(50f, emittedCurves.first().start.y, 0.01f)
    }

    @Test
    fun testEraserToolDrawsCleanly() {
        val emittedCurves = mutableListOf<BezierCurve>()
        val handler = TouchInputHandler(
            onStrokeSegment = { curve, _, _ ->
                emittedCurves.add(curve)
            }
        )

        val downEvent = createMotionEvent(MotionEvent.ACTION_DOWN, 50f, 50f, MotionEvent.TOOL_TYPE_ERASER)
        val handledDown = handler.handleTouchEvent(downEvent)
        assertTrue("Eraser touch down should be handled", handledDown)
        assertTrue(handler.isStylusActive)
    }

    @Test
    fun testHardwareEraserToolTypeTriggersEraserMode() {
        val handler = TouchInputHandler(
            toolMode = SmartDrawView.ToolMode.PAINTBRUSH,
            onStrokeSegment = { _, _, _ -> }
        )
        assertFalse(handler.isHardwareEraserActive)
        assertEquals(SmartDrawView.ToolMode.PAINTBRUSH, handler.effectiveToolMode)

        val downEvent = createMotionEvent(MotionEvent.ACTION_DOWN, 50f, 50f, MotionEvent.TOOL_TYPE_ERASER)
        handler.handleTouchEvent(downEvent)

        assertTrue(handler.isStylusActive)
        assertTrue(handler.isHardwareEraserActive)
        assertEquals(SmartDrawView.ToolMode.ERASER, handler.effectiveToolMode)

        val upEvent = createMotionEvent(MotionEvent.ACTION_UP, 50f, 50f, MotionEvent.TOOL_TYPE_ERASER)
        handler.handleTouchEvent(upEvent)

        assertFalse(handler.isStylusActive)
        assertFalse(handler.isHardwareEraserActive)
        assertEquals(SmartDrawView.ToolMode.PAINTBRUSH, handler.effectiveToolMode)
    }

    @Test
    fun testStylusButtonTriggersHardwareEraserMode() {
        val handler = TouchInputHandler(
            toolMode = SmartDrawView.ToolMode.PEN,
            onStrokeSegment = { _, _, _ -> }
        )

        // Down with primary stylus button held
        val downPrimary = createMotionEvent(
            MotionEvent.ACTION_DOWN,
            50f,
            50f,
            MotionEvent.TOOL_TYPE_STYLUS,
            buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY
        )
        handler.handleTouchEvent(downPrimary)

        assertTrue(handler.isStylusActive)
        assertTrue(handler.isHardwareEraserActive)
        assertEquals(SmartDrawView.ToolMode.ERASER, handler.effectiveToolMode)

        val upPrimary = createMotionEvent(MotionEvent.ACTION_UP, 50f, 50f, MotionEvent.TOOL_TYPE_STYLUS)
        handler.handleTouchEvent(upPrimary)
        assertFalse(handler.isHardwareEraserActive)
        assertEquals(SmartDrawView.ToolMode.PEN, handler.effectiveToolMode)

        // Down with secondary stylus button held
        val downSecondary = createMotionEvent(
            MotionEvent.ACTION_DOWN,
            60f,
            60f,
            MotionEvent.TOOL_TYPE_STYLUS,
            buttonState = MotionEvent.BUTTON_STYLUS_SECONDARY
        )
        handler.handleTouchEvent(downSecondary)

        assertTrue(handler.isHardwareEraserActive)
        assertEquals(SmartDrawView.ToolMode.ERASER, handler.effectiveToolMode)

        val upSecondary = createMotionEvent(MotionEvent.ACTION_UP, 60f, 60f, MotionEvent.TOOL_TYPE_STYLUS)
        handler.handleTouchEvent(upSecondary)
        assertFalse(handler.isHardwareEraserActive)
    }

    @Test
    fun testHardwareEraserUsesEraserRadiusAndOpacity() {
        var emittedRadius = 0f
        var emittedOpacity = 0f

        val handler = TouchInputHandler(
            baseRadius = 5.0f,
            baseOpacity = 0.5f,
            eraserRadius = 30.0f,
            eraserOpacity = 1.0f,
            toolMode = SmartDrawView.ToolMode.PEN,
            onStrokeSegment = { _, r, o ->
                emittedRadius = r
                emittedOpacity = o
            }
        )

        // Normal stroke: uses PEN (baseRadius=5f, baseOpacity=0.5f, constant pressure)
        val downPen = createMotionEvent(MotionEvent.ACTION_DOWN, 10f, 10f, MotionEvent.TOOL_TYPE_STYLUS, pressure = 0.5f)
        handler.handleTouchEvent(downPen)
        val movePen = createMotionEvent(MotionEvent.ACTION_MOVE, 20f, 20f, MotionEvent.TOOL_TYPE_STYLUS, pressure = 0.5f)
        handler.handleTouchEvent(movePen)
        val upPen = createMotionEvent(MotionEvent.ACTION_UP, 30f, 30f, MotionEvent.TOOL_TYPE_STYLUS, pressure = 0.5f)
        handler.handleTouchEvent(upPen)

        assertEquals(5.0f, emittedRadius, 0.001f)
        assertEquals(0.5f, emittedOpacity, 0.001f)

        // Hardware eraser stroke with full pressure: uses ERASER (eraserRadius=30f, eraserOpacity=1.0f)
        val downEraser = createMotionEvent(
            MotionEvent.ACTION_DOWN,
            10f,
            10f,
            MotionEvent.TOOL_TYPE_STYLUS,
            pressure = 1.0f,
            buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY
        )
        handler.handleTouchEvent(downEraser)
        val moveEraser = createMotionEvent(
            MotionEvent.ACTION_MOVE,
            20f,
            20f,
            MotionEvent.TOOL_TYPE_STYLUS,
            pressure = 1.0f,
            buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY
        )
        handler.handleTouchEvent(moveEraser)
        val upEraser = createMotionEvent(MotionEvent.ACTION_UP, 30f, 30f, MotionEvent.TOOL_TYPE_STYLUS, pressure = 1.0f)
        handler.handleTouchEvent(upEraser)

        assertEquals(30.0f, emittedRadius, 0.001f)
        assertEquals(1.0f, emittedOpacity, 0.001f)
    }

    @Test
    fun testZeroPressureLiftoffProducesMinimumSizeNotMaxSize() {
        val emittedCurves = mutableListOf<BezierCurve>()
        val emittedRadii = mutableListOf<Float>()

        val baseRadius = 20.0f
        val minPressureFactor = 0.1f
        val handler = TouchInputHandler(
            baseRadius = baseRadius,
            minPressureFactor = minPressureFactor,
            toolMode = SmartDrawView.ToolMode.PAINTBRUSH,
            onStrokeSegment = { curve, radius, _ ->
                emittedCurves.add(curve)
                emittedRadii.add(radius)
            }
        )

        // Verify normalizePressure directly: 0.0f raw pressure must evaluate to minPressureFactor, not jump to 0.5f or 1.0f
        val zeroNorm = handler.normalizePressure(0.0f)
        assertEquals("Normalized pressure for 0.0f raw must equal minPressureFactor", minPressureFactor, zeroNorm, 0.001f)

        val fullNorm = handler.normalizePressure(1.0f)
        assertEquals("Normalized pressure for 1.0f raw must equal 1.0f", 1.0f, fullNorm, 0.001f)

        // Simulate a stroke starting firm, moving, tapering down to zero pressure, and lifting off at zero pressure
        val down = createMotionEvent(MotionEvent.ACTION_DOWN, 10f, 10f, MotionEvent.TOOL_TYPE_STYLUS, pressure = 0.8f)
        handler.handleTouchEvent(down)

        val move1 = createMotionEvent(MotionEvent.ACTION_MOVE, 20f, 20f, MotionEvent.TOOL_TYPE_STYLUS, pressure = 0.4f)
        handler.handleTouchEvent(move1)

        val move2 = createMotionEvent(MotionEvent.ACTION_MOVE, 30f, 30f, MotionEvent.TOOL_TYPE_STYLUS, pressure = 0.05f)
        handler.handleTouchEvent(move2)

        // Zero pressure event right before liftoff (e.g. S-Pen tip feathering off the glass)
        val moveZero = createMotionEvent(MotionEvent.ACTION_MOVE, 40f, 40f, MotionEvent.TOOL_TYPE_STYLUS, pressure = 0.0f)
        handler.handleTouchEvent(moveZero)

        // Liftoff at 0.0f pressure
        val upZero = createMotionEvent(MotionEvent.ACTION_UP, 45f, 45f, MotionEvent.TOOL_TYPE_STYLUS, pressure = 0.0f)
        handler.handleTouchEvent(upZero)

        assertTrue("Segments should have been emitted", emittedCurves.isNotEmpty())

        // The end point pressure of the stroke must taper to minimum pressure, never jumping up
        val lastCurve = emittedCurves.last()
        assertEquals("Last curve endpoint pressure must be minPressureFactor", minPressureFactor, lastCurve.end.pressure, 0.001f)

        // The final segment radius must reflect minimum radius (baseRadius * minPressureFactor), not blot/jump
        val expectedMinRadius = baseRadius * minPressureFactor
        val lastRadius = emittedRadii.last()
        assertTrue(
            "Final segment radius ($lastRadius) should be close to min radius ($expectedMinRadius) and far less than baseRadius ($baseRadius)",
            lastRadius <= expectedMinRadius * 1.5f
        )
    }
}
