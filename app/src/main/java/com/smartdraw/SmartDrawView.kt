package com.smartdraw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import com.smartdraw.core.BezierCurve
import com.smartdraw.gles.SmartDrawRenderer
import com.smartdraw.input.TouchInputHandler
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class SmartDrawView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    enum class ToolMode {
        PENCIL,
        SMUDGE
    }

    var toolMode: ToolMode = ToolMode.PENCIL
    var brushColor: Int = Color.BLACK
    var useReferenceColors: Boolean = false
    var brushRadius: Float = 20.0f
    var brushOpacity: Float = 1.0f

    private var rendererInstance: SmartDrawRenderer? = null
    private var surfaceWidth = 1024
    private var surfaceHeight = 1024

    private val touchHandler = TouchInputHandler(
        baseRadius = brushRadius,
        baseOpacity = brushOpacity,
        onStrokeSegment = { curve, radius, opacity ->
            queueEvent {
                val r = rendererInstance ?: return@queueEvent
                when (toolMode) {
                    ToolMode.PENCIL -> {
                        r.drawBezierStroke(
                            curve = curve,
                            baseRadius = radius,
                            defaultColor = brushColor,
                            useReferenceColors = useReferenceColors,
                            spacingRatio = 0.2f
                        )
                    }
                    ToolMode.SMUDGE -> {
                        r.smudgeBezier(
                            curve = curve,
                            radius = radius,
                            blurStrength = 2.5f,
                            opacity = opacity,
                            spacingRatio = 0.2f
                        )
                    }
                }
                requestRender()
            }
        }
    )

    init {
        setEGLContextClientVersion(3)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        rendererInstance = SmartDrawRenderer(surfaceWidth, surfaceHeight)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val r = rendererInstance ?: return
        r.composite()
    }

    fun setReferenceImage(bitmap: Bitmap) {
        queueEvent {
            rendererInstance?.referenceLayer?.setBitmap(bitmap)
            requestRender()
        }
    }

    fun setReferenceOpacity(opacity: Float) {
        queueEvent {
            rendererInstance?.referenceLayer?.opacity = opacity
            requestRender()
        }
    }

    fun setReferenceVisible(visible: Boolean) {
        queueEvent {
            rendererInstance?.referenceLayer?.isVisible = visible
            requestRender()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        touchHandler.baseRadius = brushRadius
        touchHandler.baseOpacity = brushOpacity
        return touchHandler.handleTouchEvent(event) || super.onTouchEvent(event)
    }

    fun exportBitmap(onExported: (Bitmap) -> Unit) {
        queueEvent {
            val bmp = rendererInstance?.exportCompositeBitmap()
            if (bmp != null) {
                post { onExported(bmp) }
            }
        }
    }

    fun release() {
        queueEvent {
            rendererInstance?.release()
            rendererInstance = null
        }
    }
}
