package com.smartdraw.input

import android.view.MotionEvent
import com.smartdraw.SmartDrawView
import com.smartdraw.core.BezierCurve
import com.smartdraw.core.Point2D
import com.smartdraw.core.StrokeSmoother
import kotlin.math.pow

/**
 * Touch and Stylus Input Handler with Hardware Palm Rejection.
 *
 * Enforces Pen-Only Drawing:
 * - Paint strokes, smudge strokes, and Bezier curves strictly require MotionEvent pointer tool type
 *   TOOL_TYPE_STYLUS or TOOL_TYPE_ERASER.
 * - Single-finger touches, resting palms, or wrist contacts (TOOL_TYPE_FINGER) are rejected and ignored,
 *   preventing accidental stray marks. Finger gestures are reserved exclusively for two-finger
 *   viewport navigation (pan, pinch-to-zoom, and rotation) in SmartDrawView.
 * - Locks activeStylusPointerId during a stroke so simultaneous multi-touch palm contacts do not
 *   perturb stroke tracking or coordinate inversion.
 */
class TouchInputHandler(
    var baseRadius: Float = 20.0f,
    var baseOpacity: Float = 1.0f,
    var eraserRadius: Float = 20.0f,
    var eraserOpacity: Float = 1.0f,
    var pressureGamma: Float = 1.0f,
    var minPressureFactor: Float = 0.1f,
    var toolMode: SmartDrawView.ToolMode = SmartDrawView.ToolMode.PAINTBRUSH,
    var screenToCanvasTransformer: ((Float, Float) -> Pair<Float, Float>)? = null,
    var transformPoint: ((Float, Float) -> Pair<Float, Float>)? = null,
    private val onStrokeStart: ((Point2D) -> Unit)? = null,
    private val onStrokeSegment: (BezierCurve, Float, Float) -> Unit, // curve, radius, opacity
    private val onStrokeEnd: (() -> Unit)? = null,
    private val onStrokeCancel: (() -> Unit)? = null
) {
    companion object {
        private const val STYLUS_ERASER_BUTTONS =
            MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY

        fun computeEffectiveRadiusAndOpacity(
            toolMode: SmartDrawView.ToolMode,
            baseRadius: Float,
            baseOpacity: Float,
            pressure: Float
        ): Pair<Float, Float> {
            val p = pressure.coerceIn(0.05f, 1.0f)
            return when (toolMode) {
                SmartDrawView.ToolMode.PAINTBRUSH -> Pair(baseRadius * p, baseOpacity)
                SmartDrawView.ToolMode.PEN -> Pair(baseRadius, baseOpacity)
                SmartDrawView.ToolMode.ERASER -> Pair(baseRadius * p, baseOpacity)
                SmartDrawView.ToolMode.SMUDGE -> Pair(baseRadius * p, baseOpacity * p)
            }
        }
    }

    // Pointer ID of the active drawing stylus, or null if no stylus is currently down
    var activeStylusPointerId: Int? = null
        private set

    val isStylusActive: Boolean
        get() = activeStylusPointerId != null

    var isHardwareEraserActive: Boolean = false
        private set

    val effectiveToolMode: SmartDrawView.ToolMode
        get() = if (isHardwareEraserActive) SmartDrawView.ToolMode.ERASER else toolMode

    private val smoother = StrokeSmoother { curve ->
        // Evaluate dynamic radius and opacity from curve endpoints according to active tool mode dynamics
        val avgPressure = ((curve.start.pressure + curve.end.pressure) * 0.5f).coerceIn(0.05f, 1.0f)
        val activeMode = effectiveToolMode
        val curBaseRadius = if (isHardwareEraserActive) eraserRadius else baseRadius
        val curBaseOpacity = if (isHardwareEraserActive) eraserOpacity else baseOpacity
        val (dynamicRadius, dynamicOpacity) = computeEffectiveRadiusAndOpacity(
            toolMode = activeMode,
            baseRadius = curBaseRadius,
            baseOpacity = curBaseOpacity,
            pressure = avgPressure
        )
        onStrokeSegment(curve, dynamicRadius, dynamicOpacity)
    }

    fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val toolType = event.getToolType(0)
                if (isStylusTool(toolType)) {
                    val pointerId = event.getPointerId(0)
                    activeStylusPointerId = pointerId
                    val isHardwareEraser = (toolType == MotionEvent.TOOL_TYPE_ERASER) ||
                        ((event.buttonState and STYLUS_ERASER_BUTTONS) != 0)
                    isHardwareEraserActive = isHardwareEraser
                    val (cx, cy) = transformCoords(event.x, event.y)
                    val p = normalizePressure(event.pressure)
                    val pt = Point2D(cx, cy, p)
                    onStrokeStart?.invoke(pt)
                    smoother.onPointDown(pt)
                    return true
                } else {
                    // Finger touches or unknown pointer types never draw
                    return false
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val actionIndex = event.actionIndex
                val toolType = event.getToolType(actionIndex)
                // If a stylus touches down while no stylus was active, start drawing with it
                if (isStylusTool(toolType) && activeStylusPointerId == null) {
                    val pointerId = event.getPointerId(actionIndex)
                    activeStylusPointerId = pointerId
                    val isHardwareEraser = (toolType == MotionEvent.TOOL_TYPE_ERASER) ||
                        ((event.buttonState and STYLUS_ERASER_BUTTONS) != 0)
                    isHardwareEraserActive = isHardwareEraser
                    val (cx, cy) = transformCoords(event.getX(actionIndex), event.getY(actionIndex))
                    val p = normalizePressure(event.getPressure(actionIndex))
                    val pt = Point2D(cx, cy, p)
                    onStrokeStart?.invoke(pt)
                    smoother.onPointDown(pt)
                    return true
                }
                return activeStylusPointerId != null
            }

            MotionEvent.ACTION_MOVE -> {
                val stylusId = activeStylusPointerId ?: return false
                val pointerIndex = event.findPointerIndex(stylusId)
                if (pointerIndex == -1) return false

                val toolType = event.getToolType(pointerIndex)
                if (toolType == MotionEvent.TOOL_TYPE_ERASER ||
                    ((event.buttonState and STYLUS_ERASER_BUTTONS) != 0)
                ) {
                    isHardwareEraserActive = true
                }

                // Process historical points for the active stylus pointer
                val historySize = event.historySize
                for (h in 0 until historySize) {
                    val hx = event.getHistoricalX(pointerIndex, h)
                    val hy = event.getHistoricalY(pointerIndex, h)
                    val hp = normalizePressure(event.getHistoricalPressure(pointerIndex, h))
                    val (cx, cy) = transformCoords(hx, hy)
                    smoother.onPointMove(Point2D(cx, cy, hp))
                }

                val curX = event.getX(pointerIndex)
                val curY = event.getY(pointerIndex)
                val p = normalizePressure(event.getPressure(pointerIndex))
                val (cx, cy) = transformCoords(curX, curY)
                smoother.onPointMove(Point2D(cx, cy, p))
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                if (pointerId == activeStylusPointerId) {
                    val (cx, cy) = transformCoords(event.getX(actionIndex), event.getY(actionIndex))
                    val p = normalizePressure(event.getPressure(actionIndex))
                    smoother.onPointUp(Point2D(cx, cy, p))
                    activeStylusPointerId = null
                    onStrokeEnd?.invoke()
                    isHardwareEraserActive = false
                    return true
                }
                return activeStylusPointerId != null
            }

            MotionEvent.ACTION_UP -> {
                val stylusId = activeStylusPointerId
                if (stylusId != null) {
                    val pointerIndex = event.findPointerIndex(stylusId)
                    if (pointerIndex != -1) {
                        val (cx, cy) = transformCoords(event.getX(pointerIndex), event.getY(pointerIndex))
                        val p = normalizePressure(event.getPressure(pointerIndex))
                        smoother.onPointUp(Point2D(cx, cy, p))
                    } else {
                        smoother.reset()
                    }
                    activeStylusPointerId = null
                    onStrokeEnd?.invoke()
                    isHardwareEraserActive = false
                    return true
                }
                return false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (activeStylusPointerId != null) {
                    smoother.reset()
                    activeStylusPointerId = null
                    onStrokeCancel?.invoke() ?: onStrokeEnd?.invoke()
                    isHardwareEraserActive = false
                    return true
                }
                return false
            }
        }
        return false
    }

    fun cancelStroke() {
        if (activeStylusPointerId != null) {
            smoother.reset()
            activeStylusPointerId = null
            onStrokeCancel?.invoke() ?: onStrokeEnd?.invoke()
            isHardwareEraserActive = false
        }
    }

    private fun isStylusTool(toolType: Int): Boolean {
        return toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun transformCoords(screenX: Float, screenY: Float): Pair<Float, Float> {
        val transformer = screenToCanvasTransformer ?: transformPoint ?: return Pair(screenX, screenY)
        return transformer(screenX, screenY)
    }

    fun normalizePressure(raw: Float): Float {
        val clamped = raw.coerceIn(0.0f, 1.0f)
        val shaped = clamped.pow(pressureGamma)
        return minPressureFactor + (1.0f - minPressureFactor) * shaped
    }
}
