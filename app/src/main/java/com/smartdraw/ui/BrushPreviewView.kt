package com.smartdraw.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A custom view that renders a live circular preview dot representing the current brush radius and opacity.
 */
class BrushPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var brushRadius: Float = 16.0f
        set(value) {
            field = value
            invalidate()
        }

    var brushOpacity: Float = 1.0f
        set(value) {
            field = value
            invalidate()
        }

    var previewColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#424242")
        strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = (minOf(width, height) / 2f) - 4f

        // Draw dotted/subtle boundary ring for the container max boundary
        canvas.drawCircle(cx, cy, maxRadius, strokePaint)

        // Draw the preview dot scaled up to maxRadius
        val drawRadius = minOf(brushRadius, maxRadius).coerceAtLeast(1.5f)
        val alpha = (brushOpacity.coerceIn(0.01f, 1.0f) * 255).toInt()
        fillPaint.color = previewColor
        fillPaint.alpha = alpha

        canvas.drawCircle(cx, cy, drawRadius, fillPaint)
    }
}
