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
        pressure: Float = 0.5f
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
            0,
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
}
