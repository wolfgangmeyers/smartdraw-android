package com.smartdraw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.smartdraw.core.BezierCurve
import com.smartdraw.gles.SmartDrawRenderer
import com.smartdraw.input.TouchInputHandler
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot

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

    // Fixed intrinsic canvas dimensions for offscreen drawing
    val canvasWidth = 1024
    val canvasHeight = 1024

    // Viewport affine transform state: Screen = viewportScale * Canvas + viewportTranslation
    var viewportTranslationX: Float = 0f
        private set
    var viewportTranslationY: Float = 0f
        private set
    var viewportScale: Float = 1.0f
        private set

    companion object {
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 10.0f
    }

    private var rendererInstance: SmartDrawRenderer? = null
    private var surfaceWidth = 1024
    private var surfaceHeight = 1024

    private var prevFocalX = 0f
    private var prevFocalY = 0f
    private var isNavigating = false

    private val touchHandler = TouchInputHandler(
        baseRadius = brushRadius,
        baseOpacity = brushOpacity,
        screenToCanvasTransformer = { sx, sy ->
            screenToCanvas(sx, sy)
        },
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

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (touchHandler.isStylusActive) return false

            val factor = detector.scaleFactor
            if (factor <= 0f) return true

            val oldScale = viewportScale
            val newScale = (oldScale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
            val focusX = detector.focusX
            val focusY = detector.focusY

            // Zoom anchored at focal point:
            // T' = F - (newScale / oldScale) * (F - T)
            val scaleRatio = newScale / oldScale
            viewportTranslationX = focusX - scaleRatio * (focusX - viewportTranslationX)
            viewportTranslationY = focusY - scaleRatio * (focusY - viewportTranslationY)
            viewportScale = newScale

            requestRender()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (touchHandler.isStylusActive) return false
            resetView()
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        rendererInstance = SmartDrawRenderer(canvasWidth, canvasHeight)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        val oldW = surfaceWidth
        val oldH = surfaceHeight
        surfaceWidth = width
        surfaceHeight = height

        // Center canvas initially or adjust on window resize
        if (oldW == canvasWidth && oldH == canvasHeight && viewportTranslationX == 0f && viewportTranslationY == 0f) {
            centerCanvas(width, height)
        } else {
            // SPECIFICATION.md §4.4: Maintain canvas center point relative to viewport
            viewportTranslationX += (width - oldW) * 0.5f
            viewportTranslationY += (height - oldH) * 0.5f
        }
        requestRender()
    }

    override fun onDrawFrame(gl: GL10?) {
        val r = rendererInstance ?: return
        // Render composite output directly to the default screen framebuffer (0) transformed by viewport MVP
        r.composite(
            targetFramebuffer = 0,
            viewportW = surfaceWidth,
            viewportH = surfaceHeight,
            translationX = viewportTranslationX,
            translationY = viewportTranslationY,
            scale = viewportScale
        )
    }

    /**
     * Map screen coordinates to intrinsic canvas space:
     * x_c = (x_s - T_x) / S
     * y_c = (y_s - T_y) / S
     */
    fun screenToCanvas(screenX: Float, screenY: Float): Pair<Float, Float> {
        val s = if (viewportScale != 0f) viewportScale else 1.0f
        val cx = (screenX - viewportTranslationX) / s
        val cy = (screenY - viewportTranslationY) / s
        return Pair(cx, cy)
    }

    /**
     * Map intrinsic canvas coordinates to screen space:
     * x_s = S * x_c + T_x
     * y_s = S * y_c + T_y
     */
    fun canvasToScreen(canvasX: Float, canvasY: Float): Pair<Float, Float> {
        val sx = viewportScale * canvasX + viewportTranslationX
        val sy = viewportScale * canvasY + viewportTranslationY
        return Pair(sx, sy)
    }

    /**
     * Center canvas in the viewport at 1:1 scale (scale = 1.0f).
     */
    fun resetView() {
        viewportScale = 1.0f
        centerCanvas(surfaceWidth, surfaceHeight)
        requestRender()
    }

    private fun centerCanvas(w: Int, h: Int) {
        viewportTranslationX = (w - canvasWidth * viewportScale) * 0.5f
        viewportTranslationY = (h - canvasHeight * viewportScale) * 0.5f
    }

    fun setReferenceImage(bitmap: Bitmap) {
        queueEvent {
            rendererInstance?.referenceLayer?.setBitmap(bitmap)
            requestRender()
        }
    }

    fun loadPaintingBitmap(bitmap: Bitmap) {
        queueEvent {
            rendererInstance?.loadBaseBitmap(bitmap)
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

        // Check pointer tool types in event
        var hasStylus = false
        var fingerCount = 0
        for (i in 0 until event.pointerCount) {
            val toolType = event.getToolType(i)
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                hasStylus = true
            } else if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
                fingerCount++
            }
        }

        // STYLUS PRIORITY: If stylus is touching or active, cancel any finger navigation and route to touchHandler
        if (hasStylus || touchHandler.isStylusActive) {
            if (isNavigating) {
                isNavigating = false
            }
            return touchHandler.handleTouchEvent(event)
        }

        // If no stylus, stylus drawing cannot occur. Handle two-finger gestures and double-tap reset.
        gestureDetector.onTouchEvent(event)

        if (fingerCount == 2) {
            scaleDetector.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isNavigating = false
                // Single finger touch down is ignored/rejected for drawing and navigation
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (fingerCount == 2) {
                    isNavigating = true
                    // Compute initial focal point between the two finger contacts
                    val fingerIndices = mutableListOf<Int>()
                    for (i in 0 until event.pointerCount) {
                        if (event.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER) {
                            fingerIndices.add(i)
                        }
                    }
                    if (fingerIndices.size >= 2) {
                        prevFocalX = (event.getX(fingerIndices[0]) + event.getX(fingerIndices[1])) * 0.5f
                        prevFocalY = (event.getY(fingerIndices[0]) + event.getY(fingerIndices[1])) * 0.5f
                    }
                } else {
                    isNavigating = false
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (fingerCount == 2 && isNavigating && !scaleDetector.isInProgress) {
                    val fingerIndices = mutableListOf<Int>()
                    for (i in 0 until event.pointerCount) {
                        if (event.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER) {
                            fingerIndices.add(i)
                        }
                    }
                    if (fingerIndices.size >= 2) {
                        val focalX = (event.getX(fingerIndices[0]) + event.getX(fingerIndices[1])) * 0.5f
                        val focalY = (event.getY(fingerIndices[0]) + event.getY(fingerIndices[1])) * 0.5f

                        // Pan delta
                        val dx = focalX - prevFocalX
                        val dy = focalY - prevFocalY

                        viewportTranslationX += dx
                        viewportTranslationY += dy

                        prevFocalX = focalX
                        prevFocalY = focalY

                        requestRender()
                    }
                    return true
                } else if (fingerCount == 2 && isNavigating) {
                    // When scale gesture is in progress, update prevFocal from detector focus
                    prevFocalX = scaleDetector.focusX
                    prevFocalY = scaleDetector.focusY
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val remainingFingers = fingerCount - 1
                if (remainingFingers == 2) {
                    // Update focal point for remaining two fingers
                    val remainingIndices = mutableListOf<Int>()
                    for (i in 0 until event.pointerCount) {
                        if (i != event.actionIndex && event.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER) {
                            remainingIndices.add(i)
                        }
                    }
                    if (remainingIndices.size >= 2) {
                        prevFocalX = (event.getX(remainingIndices[0]) + event.getX(remainingIndices[1])) * 0.5f
                        prevFocalY = (event.getY(remainingIndices[0]) + event.getY(remainingIndices[1])) * 0.5f
                    }
                } else {
                    isNavigating = false
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isNavigating = false
                return true
            }
        }

        return true
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
