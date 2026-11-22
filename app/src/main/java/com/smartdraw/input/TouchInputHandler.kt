package com.smartdraw.input

import android.view.MotionEvent
import com.smartdraw.core.BezierCurve
import com.smartdraw.core.Point2D
import com.smartdraw.core.StrokeSmoother
import kotlin.math.pow

class TouchInputHandler(
    var baseRadius: Float = 20.0f,
    var baseOpacity: Float = 1.0f,
    var pressureGamma: Float = 1.0f,
    var minPressureFactor: Float = 0.1f,
    var transformPoint: ((Float, Float) -> Pair<Float, Float>)? = null,
    private val onStrokeStart: ((Point2D) -> Unit)? = null,
    private val onStrokeSegment: (BezierCurve, Float, Float) -> Unit, // curve, radius, opacity
    private val onStrokeEnd: (() -> Unit)? = null,
    private val onStrokeCancel: (() -> Unit)? = null
) {
    private val smoother = StrokeSmoother { curve ->
        // Evaluate dynamic radius and opacity from curve endpoints
        val avgPressure = ((curve.start.pressure + curve.end.pressure) * 0.5f).coerceIn(0.05f, 1.0f)
        val scaledPressure = minPressureFactor + (1.0f - minPressureFactor) * avgPressure.pow(pressureGamma)
        val radius = baseRadius * scaledPressure
        val opacity = (baseOpacity * scaledPressure).coerceIn(0.05f, 1.0f)
        onStrokeSegment(curve, radius, opacity)
    }

    fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (cx, cy) = transformPoint?.invoke(event.x, event.y) ?: Pair(event.x, event.y)
                val p = normalizePressure(event.pressure)
                val pt = Point2D(cx, cy, p)
                onStrokeStart?.invoke(pt)
                smoother.onPointDown(pt)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val historySize = event.historySize
                for (h in 0 until historySize) {
                    val (hx, hy) = transformPoint?.invoke(event.getHistoricalX(h), event.getHistoricalY(h))
                        ?: Pair(event.getHistoricalX(h), event.getHistoricalY(h))
                    val hp = normalizePressure(event.getHistoricalPressure(h))
                    smoother.onPointMove(Point2D(hx, hy, hp))
                }
                val (cx, cy) = transformPoint?.invoke(event.x, event.y) ?: Pair(event.x, event.y)
                val p = normalizePressure(event.pressure)
                smoother.onPointMove(Point2D(cx, cy, p))
                return true
            }

            MotionEvent.ACTION_UP -> {
                val (cx, cy) = transformPoint?.invoke(event.x, event.y) ?: Pair(event.x, event.y)
                val p = normalizePressure(event.pressure)
                smoother.onPointUp(Point2D(cx, cy, p))
                onStrokeEnd?.invoke()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                smoother.reset()
                onStrokeCancel?.invoke() ?: onStrokeEnd?.invoke()
                return true
            }
        }
        return false
    }

    private fun normalizePressure(raw: Float): Float {
        // Some devices report 0 for touch if pressure is uncalibrated or default 1.0
        if (raw <= 0.001f) return 0.5f
        return raw.coerceIn(0.05f, 1.0f)
    }
}
