package com.smartdraw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
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
    val canvasWidth = 1024
    val canvasHeight = 1024
    private var viewWidth = 1024
    private var viewHeight = 1024

    private var activeStrokeStartColor: Int = Color.BLACK

    data class Viewport(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height
    }

    fun getCanvasViewport(): Viewport {
        val vw = if (viewWidth > 0) viewWidth else canvasWidth
        val vh = if (viewHeight > 0) viewHeight else canvasHeight
        val canvasAspect = canvasWidth.toFloat() / canvasHeight.toFloat()
        val viewAspect = vw.toFloat() / vh.toFloat()

        val vpWidth: Int
        val vpHeight: Int
        val vpLeft: Int
        val vpTop: Int

        if (viewAspect > canvasAspect) {
            // View is wider than canvas -> fit height
            vpHeight = vh
            vpWidth = (vh * canvasAspect).toInt()
            vpLeft = (vw - vpWidth) / 2
            vpTop = 0
        } else {
            // View is taller than or equal to canvas -> fit width
            vpWidth = vw
            vpHeight = (vw / canvasAspect).toInt()
            vpLeft = 0
            vpTop = (vh - vpHeight) / 2
        }

        return Viewport(vpLeft, vpTop, vpWidth, vpHeight)
    }

    fun viewToCanvas(touchX: Float, touchY: Float): Pair<Float, Float> {
        val vp = getCanvasViewport()
        val canvasX = (touchX - vp.left) * (canvasWidth.toFloat() / vp.width.toFloat())
        val canvasY = (touchY - vp.top) * (canvasHeight.toFloat() / vp.height.toFloat())
        return Pair(canvasX, canvasY)
    }

    private val touchHandler = TouchInputHandler(
        baseRadius = brushRadius,
        baseOpacity = brushOpacity,
        transformPoint = { x, y -> viewToCanvas(x, y) },
        onStrokeStart = { pt ->
            val preview = if (useReferenceColors) {
                rendererInstance?.referenceLayer?.sampleColor(pt.x, pt.y, brushColor) ?: brushColor
            } else {
                brushColor
            }
            activeStrokeStartColor = preview
            queueEvent {
                val r = rendererInstance ?: return@queueEvent
                if (toolMode == ToolMode.PENCIL) {
                    r.beginActiveStroke(preview)
                }
                requestRender()
            }
        },
        onStrokeSegment = { curve, radius, opacity ->
            queueEvent {
                val r = rendererInstance ?: return@queueEvent
                when (toolMode) {
                    ToolMode.PENCIL -> {
                        r.drawActiveStrokeSegment(
                            curve = curve,
                            radius = radius,
                            opacity = opacity,
                            previewColor = activeStrokeStartColor,
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
        },
        onStrokeEnd = {
            queueEvent {
                val r = rendererInstance ?: return@queueEvent
                if (toolMode == ToolMode.PENCIL) {
                    r.endActiveStroke(
                        useReferenceColors = useReferenceColors,
                        defaultColor = brushColor,
                        spacingRatio = 0.2f
                    )
                }
                requestRender()
            }
        },
        onStrokeCancel = {
            queueEvent {
                rendererInstance?.clearActiveStroke()
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
        rendererInstance = SmartDrawRenderer(canvasWidth, canvasHeight)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val r = rendererInstance ?: return
        val vp = getCanvasViewport()

        // Bind framebuffer 0 (the screen)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        // Clear background to dark gray
        GLES30.glViewport(0, 0, viewWidth, viewHeight)
        GLES30.glClearColor(0.12f, 0.12f, 0.12f, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // Set viewport to the canvas viewport
        GLES30.glViewport(vp.left, viewHeight - vp.bottom, vp.width, vp.height)

        // Render the composited canvas to the screen!
        r.renderToScreen()
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
