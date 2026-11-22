package com.smartdraw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
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
        get() = if (::touchHandler.isInitialized) touchHandler.baseRadius else field
        set(value) {
            field = value
            if (::touchHandler.isInitialized) {
                touchHandler.baseRadius = value
            }
        }
    var brushOpacity: Float = 1.0f
        get() = if (::touchHandler.isInitialized) touchHandler.baseOpacity else field
        set(value) {
            field = value
            if (::touchHandler.isInitialized) {
                touchHandler.baseOpacity = value
            }
        }

    // Intrinsic canvas dimensions for offscreen drawing (dynamic, defaults to 1024x1024)
    var canvasWidth: Int = 1024
        private set
    var canvasHeight: Int = 1024
        private set

    // Viewport affine transform state: Screen = R(theta) * (viewportScale * Canvas) + viewportTranslation
    var viewportTranslationX: Float = 0f
        private set
    var viewportTranslationY: Float = 0f
        private set
    var viewportScale: Float = 1.0f
        private set
    var viewportRotationDegrees: Float = 0f
        private set

    companion object {
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 10.0f
    }

    private var rendererInstance: SmartDrawRenderer? = null
    private var surfaceWidth = 1024
    private var surfaceHeight = 1024

    private var activeStrokeStartColor: Int = Color.BLACK

    data class Viewport(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height
    }

    fun getCanvasViewport(): Viewport {
        val vw = if (surfaceWidth > 0) surfaceWidth else canvasWidth
        val vh = if (surfaceHeight > 0) surfaceHeight else canvasHeight
        val canvasAspect = canvasWidth.toFloat() / canvasHeight.toFloat()
        val viewAspect = vw.toFloat() / vh.toFloat()

        val vpWidth: Int
        val vpHeight: Int
        val vpLeft: Int
        val vpTop: Int

        if (viewAspect > canvasAspect) {
            vpHeight = vh
            vpWidth = (vh * canvasAspect).toInt()
            vpLeft = (vw - vpWidth) / 2
            vpTop = 0
        } else {
            vpWidth = vw
            vpHeight = (vw / canvasAspect).toInt()
            vpLeft = 0
            vpTop = (vh - vpHeight) / 2
        }

        return Viewport(vpLeft, vpTop, vpWidth, vpHeight)
    }

    fun viewToCanvas(touchX: Float, touchY: Float): Pair<Float, Float> {
        return screenToCanvas(touchX, touchY)
    }

    private var prevFocalX = 0f
    private var prevFocalY = 0f
    private var prevFingerDistance = 0f
    private var prevFingerAngle = 0f
    private var isNavigating = false

    private lateinit var touchHandler: TouchInputHandler

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (::touchHandler.isInitialized && touchHandler.isStylusActive) return false
            resetView()
            return true
        }
    })

    init {
        touchHandler = TouchInputHandler(
            baseRadius = brushRadius,
            baseOpacity = brushOpacity,
            screenToCanvasTransformer = { sx, sy ->
                screenToCanvas(sx, sy)
            },
            transformPoint = { sx, sy ->
                screenToCanvas(sx, sy)
            },
            onStrokeStart = { pt ->
                val preview = if (useReferenceColors) {
                    rendererInstance?.referenceLayer?.sampleColor(
                        canvasX = pt.x,
                        canvasY = pt.y,
                        canvasWidth = canvasWidth.toFloat(),
                        canvasHeight = canvasHeight.toFloat(),
                        defaultColor = brushColor
                    ) ?: brushColor
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
        r.renderToScreen(
            viewportW = surfaceWidth,
            viewportH = surfaceHeight,
            translationX = viewportTranslationX,
            translationY = viewportTranslationY,
            scale = viewportScale,
            rotationDegrees = viewportRotationDegrees
        )
    }

    /**
     * Map screen coordinates to intrinsic canvas space:
     * Screen = T + R(theta) * (S * Canvas)
     * Canvas = (1 / S) * R(-theta) * (Screen - T)
     *
     * Let dx = screenX - T_x, dy = screenY - T_y
     * Rotate (dx, dy) by -theta:
     * rx = dx * cos(-theta) - dy * sin(-theta) = dx * cos(theta) + dy * sin(theta)
     * ry = dx * sin(-theta) + dy * cos(-theta) = -dx * sin(theta) + dy * cos(theta)
     * x_c = rx / S
     * y_c = ry / S
     */
    fun screenToCanvas(screenX: Float, screenY: Float): Pair<Float, Float> {
        val s = if (viewportScale != 0f) viewportScale else 1.0f
        val dx = screenX - viewportTranslationX
        val dy = screenY - viewportTranslationY
        val rad = Math.toRadians(viewportRotationDegrees.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()

        val rx = dx * cos + dy * sin
        val ry = -dx * sin + dy * cos

        val cx = rx / s
        val cy = ry / s
        return Pair(cx, cy)
    }

    /**
     * Map intrinsic canvas coordinates to screen space:
     * Screen = T + R(theta) * (S * Canvas)
     */
    fun canvasToScreen(canvasX: Float, canvasY: Float): Pair<Float, Float> {
        val s = viewportScale
        val rad = Math.toRadians(viewportRotationDegrees.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()

        val sx = s * canvasX
        val sy = s * canvasY

        val rx = sx * cos - sy * sin
        val ry = sx * sin + sy * cos

        return Pair(rx + viewportTranslationX, ry + viewportTranslationY)
    }

    /**
     * Center canvas in the viewport, fitting it to the screen if it is larger than the surface.
     * Sets rotation to 0 degrees.
     */
    fun resetView() {
        val vw = if (surfaceWidth > 0) surfaceWidth else canvasWidth
        val vh = if (surfaceHeight > 0) surfaceHeight else canvasHeight
        
        // Scale to fit screen with padding if canvas is larger than surface
        val fitScale = minOf(1.0f, minOf(vw.toFloat() / canvasWidth.toFloat(), vh.toFloat() / canvasHeight.toFloat()))
        viewportScale = if (fitScale > 0f) fitScale else 1.0f
        viewportRotationDegrees = 0f
        centerCanvas(vw, vh)
        requestRender()
    }

    private fun centerCanvas(w: Int, h: Int) {
        viewportTranslationX = (w - canvasWidth * viewportScale) * 0.5f
        viewportTranslationY = (h - canvasHeight * viewportScale) * 0.5f
    }

    fun setBrushSize(radius: Float) {
        touchHandler.baseRadius = radius
    }

    fun getBrushSize(): Float = touchHandler.baseRadius

    fun setBrushOpacityValue(opacity: Float) {
        touchHandler.baseOpacity = opacity
    }

    fun getBrushOpacityValue(): Float = touchHandler.baseOpacity

    fun clearCanvas() {
        queueEvent {
            rendererInstance?.clearCanvas(1.0f, 1.0f, 1.0f, 1.0f)
            requestRender()
        }
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

    /**
     * Reinitialize the canvas dimensions to match a new aspect ratio and resolution.
     * Reallocates FBOs, clears to white, resets viewport scale and centers the canvas.
     */
    fun setCanvasDimensions(width: Int, height: Int) {
        canvasWidth = width
        canvasHeight = height
        queueEvent {
            rendererInstance?.resizeCanvas(width, height)
            requestRender()
        }
        resetView()
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

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isNavigating = false
                // Single finger touch down is ignored/rejected for drawing and navigation
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (fingerCount == 2) {
                    val fingerIndices = mutableListOf<Int>()
                    for (i in 0 until event.pointerCount) {
                        if (event.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER) {
                            fingerIndices.add(i)
                        }
                    }
                    if (fingerIndices.size >= 2) {
                        isNavigating = true
                        val p1x = event.getX(fingerIndices[0])
                        val p1y = event.getY(fingerIndices[0])
                        val p2x = event.getX(fingerIndices[1])
                        val p2y = event.getY(fingerIndices[1])

                        prevFocalX = (p1x + p2x) * 0.5f
                        prevFocalY = (p1y + p2y) * 0.5f
                        prevFingerDistance = hypot(p2x - p1x, p2y - p1y)
                        prevFingerAngle = Math.atan2((p2y - p1y).toDouble(), (p2x - p1x).toDouble()).toFloat()
                    }
                } else {
                    isNavigating = false
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (fingerCount == 2 && isNavigating) {
                    val fingerIndices = mutableListOf<Int>()
                    for (i in 0 until event.pointerCount) {
                        if (event.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER) {
                            fingerIndices.add(i)
                        }
                    }
                    if (fingerIndices.size >= 2) {
                        val p1x = event.getX(fingerIndices[0])
                        val p1y = event.getY(fingerIndices[0])
                        val p2x = event.getX(fingerIndices[1])
                        val p2y = event.getY(fingerIndices[1])

                        val currFocalX = (p1x + p2x) * 0.5f
                        val currFocalY = (p1y + p2y) * 0.5f
                        val currFingerDistance = hypot(p2x - p1x, p2y - p1y)
                        val currFingerAngle = Math.atan2((p2y - p1y).toDouble(), (p2x - p1x).toDouble()).toFloat()

                        if (prevFingerDistance > 0f) {
                            val rawScaleRatio = currFingerDistance / prevFingerDistance
                            val oldScale = viewportScale
                            val targetScale = (oldScale * rawScaleRatio).coerceIn(MIN_SCALE, MAX_SCALE)
                            val actualScaleRatio = if (oldScale > 0f) targetScale / oldScale else 1.0f

                            var deltaAngle = currFingerAngle - prevFingerAngle
                            while (deltaAngle > Math.PI.toFloat()) deltaAngle -= (2 * Math.PI).toFloat()
                            while (deltaAngle <= -Math.PI.toFloat()) deltaAngle += (2 * Math.PI).toFloat()

                            // Focal-anchored affine update:
                            // T_new = F_curr + R(deltaTheta) * (s * (T_prev - F_prev))
                            val dx = viewportTranslationX - prevFocalX
                            val dy = viewportTranslationY - prevFocalY

                            val scaledDx = actualScaleRatio * dx
                            val scaledDy = actualScaleRatio * dy

                            val cosD = kotlin.math.cos(deltaAngle)
                            val sinD = kotlin.math.sin(deltaAngle)

                            val rotX = cosD * scaledDx - sinD * scaledDy
                            val rotY = sinD * scaledDx + cosD * scaledDy

                            viewportTranslationX = currFocalX + rotX
                            viewportTranslationY = currFocalY + rotY
                            viewportScale = targetScale

                            var newRotation = viewportRotationDegrees + Math.toDegrees(deltaAngle.toDouble()).toFloat()
                            while (newRotation > 180f) newRotation -= 360f
                            while (newRotation <= -180f) newRotation += 360f
                            viewportRotationDegrees = newRotation

                            requestRender()
                        }

                        prevFocalX = currFocalX
                        prevFocalY = currFocalY
                        prevFingerDistance = currFingerDistance
                        prevFingerAngle = currFingerAngle
                    }
                    return true
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val remainingFingers = fingerCount - 1
                if (remainingFingers == 2) {
                    // Update focal point and distance for remaining two fingers
                    val remainingIndices = mutableListOf<Int>()
                    for (i in 0 until event.pointerCount) {
                        if (i != event.actionIndex && event.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER) {
                            remainingIndices.add(i)
                        }
                    }
                    if (remainingIndices.size >= 2) {
                        val p1x = event.getX(remainingIndices[0])
                        val p1y = event.getY(remainingIndices[0])
                        val p2x = event.getX(remainingIndices[1])
                        val p2y = event.getY(remainingIndices[1])

                        prevFocalX = (p1x + p2x) * 0.5f
                        prevFocalY = (p1y + p2y) * 0.5f
                        prevFingerDistance = hypot(p2x - p1x, p2y - p1y)
                        prevFingerAngle = Math.atan2((p2y - p1y).toDouble(), (p2x - p1x).toDouble()).toFloat()
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
