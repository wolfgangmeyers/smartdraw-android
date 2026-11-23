package com.smartdraw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import com.smartdraw.gles.CanvasLayer
import com.smartdraw.gles.GlesFbo
import com.smartdraw.gles.SmartDrawRenderer
import com.smartdraw.input.TouchInputHandler
import com.smartdraw.project.LayerMetadata
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot

class SmartDrawView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    enum class ToolMode {
        PAINTBRUSH,
        PEN,
        PENCIL,
        SMUDGE
    }

    var toolMode: ToolMode = ToolMode.PAINTBRUSH
        set(value) {
            field = value
            if (::touchHandler.isInitialized) {
                touchHandler.toolMode = value
            }
        }

    var onBrushColorChanged: ((Int) -> Unit)? = null
    var onColorSampled: ((Int) -> Unit)? = null
    var onEyedropperStateChanged: ((Boolean) -> Unit)? = null

    var isEyedropperActive: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                onEyedropperStateChanged?.invoke(value)
            }
        }

    var brushColor: Int = Color.BLACK
        set(value) {
            field = value
            onBrushColorChanged?.invoke(value)
        }
    var useReferenceColors: Boolean = false
    var isRatchetEnabled: Boolean = false
        get() = field
        private set
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

    private val layersList = mutableListOf<LayerMetadata>()
    private var internalActiveLayerId: String? = null
    var onLayersChanged: (() -> Unit)? = null

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

    // Pending GL Lifecycle Queue:
    // When bitmap or layer settings are loaded before the GL surface is initialized
    // (e.g. cold start restoration in Activity.onCreate() before onSurfaceCreated() fires),
    // rendererInstance is null. These pending fields stage data on the main thread and are
    // drained and uploaded to GPU textures inside onSurfaceCreated() on the dedicated GL render thread.
    private var pendingBaseBitmap: Bitmap? = null
    private var pendingReferenceBitmap: Bitmap? = null
    private var pendingReferenceOpacity: Float? = null
    private var pendingReferenceVisible: Boolean? = null

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

    var onStrokeCompleted: (() -> Unit)? = null

    init {
        touchHandler = TouchInputHandler(
            baseRadius = brushRadius,
            baseOpacity = brushOpacity,
            toolMode = toolMode,
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
                    if (toolMode != ToolMode.SMUDGE) {
                        r.beginActiveStroke(preview)
                    }
                    requestRender()
                }
            },
            onStrokeSegment = { curve, radius, opacity ->
                queueEvent {
                    val r = rendererInstance ?: return@queueEvent
                    when (toolMode) {
                        ToolMode.PAINTBRUSH,
                        ToolMode.PEN,
                        ToolMode.PENCIL -> {
                            val segRadius = brushRadius
                            val segOpacity = brushOpacity
                            r.drawActiveStrokeSegment(
                                curve = curve,
                                radius = segRadius,
                                opacity = segOpacity,
                                previewColor = activeStrokeStartColor,
                                spacingRatio = 0.2f,
                                toolMode = toolMode
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
                    var committed = true
                    if (toolMode != ToolMode.SMUDGE) {
                        committed = r.endActiveStroke(
                            useReferenceColors = useReferenceColors,
                            defaultColor = brushColor,
                            spacingRatio = 0.2f
                        )
                    }
                    requestRender()
                    if (committed) {
                        post { onStrokeCompleted?.invoke() }
                    }
                }
            },
            onStrokeCancel = {
                queueEvent {
                    rendererInstance?.clearActiveStroke()
                    requestRender()
                }
            }
        )

        val initialLayer = LayerMetadata(id = java.util.UUID.randomUUID().toString(), name = "Layer 1")
        layersList.add(initialLayer)
        internalActiveLayerId = initialLayer.id

        setEGLContextClientVersion(3)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val renderer = SmartDrawRenderer(canvasWidth, canvasHeight)
        renderer.isRatchetEnabled = isRatchetEnabled
        rendererInstance = renderer

        if (layersList.isNotEmpty()) {
            renderer.layers.clear()
            for (meta in layersList) {
                val cl = CanvasLayer(
                    id = meta.id,
                    name = meta.name,
                    opacity = meta.opacity,
                    isVisible = meta.isVisible,
                    fbo = GlesFbo(canvasWidth, canvasHeight)
                )
                renderer.clearLayer(cl, 0.0f, 0.0f, 0.0f, 0.0f)
                renderer.layers.add(cl)
            }
            internalActiveLayerId?.let { renderer.selectLayer(it) }
        }

        // Drain pending GL lifecycle queue: upload staged bitmaps and layer parameters
        val baseBmp = pendingBaseBitmap
        if (baseBmp != null && !baseBmp.isRecycled) {
            renderer.loadBaseBitmap(baseBmp)
            baseBmp.recycle()
        }
        pendingBaseBitmap = null

        val refBmp = pendingReferenceBitmap
        if (refBmp != null && !refBmp.isRecycled) {
            renderer.referenceLayer.setBitmap(refBmp)
        }
        pendingReferenceBitmap = null

        pendingReferenceOpacity?.let { renderer.referenceLayer.opacity = it }
        pendingReferenceOpacity = null

        pendingReferenceVisible?.let { renderer.referenceLayer.isVisible = it }
        pendingReferenceVisible = null

        requestRender()
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
     * Map screen coordinates to intrinsic canvas space via exact inverse 2D affine transform:
     * Forward Transform:
     *   Screen = T + R(theta) * (S * Canvas)
     *
     * Inverse Transform:
     *   Canvas = (1 / S) * R(-theta) * (Screen - T)
     *
     * Expanding with dx = screenX - T_x, dy = screenY - T_y and 2D rotation matrix R(-theta):
     *   [cx] = (1 / S) * [ cos(theta)   sin(theta)] [dx]
     *   [cy]   (1 / S) * [-sin(theta)   cos(theta)] [dy]
     *
     * Result:
     *   x_c = (dx * cos(theta) + dy * sin(theta)) / S
     *   y_c = (-dx * sin(theta) + dy * cos(theta)) / S
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
     * Map intrinsic canvas coordinates to screen space via forward 2D affine transform:
     *   Screen = T + R(theta) * (S * Canvas)
     *
     * Let [sx, sy] = S * [canvasX, canvasY]
     * Apply 2D rotation matrix R(theta):
     *   rx = sx * cos(theta) - sy * sin(theta)
     *   ry = sx * sin(theta) + sy * cos(theta)
     *
     * Apply translation:
     *   screenX = rx + T_x
     *   screenY = ry + T_y
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

    fun setRatchetEnabled(enabled: Boolean) {
        this.isRatchetEnabled = enabled
        if (rendererInstance != null) {
            queueEvent {
                rendererInstance?.isRatchetEnabled = enabled
            }
        }
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
        if (rendererInstance != null) {
            queueEvent {
                rendererInstance?.referenceLayer?.setBitmap(bitmap)
                requestRender()
            }
        } else {
            pendingReferenceBitmap = bitmap
        }
    }

    fun loadPaintingBitmap(bitmap: Bitmap) {
        loadBaseBitmap(bitmap)
    }

    fun setReferenceOpacity(opacity: Float) {
        if (rendererInstance != null) {
            queueEvent {
                rendererInstance?.referenceLayer?.opacity = opacity
                requestRender()
            }
        } else {
            pendingReferenceOpacity = opacity
        }
    }

    fun setReferenceVisible(visible: Boolean) {
        if (rendererInstance != null) {
            queueEvent {
                rendererInstance?.referenceLayer?.isVisible = visible
                requestRender()
            }
        } else {
            pendingReferenceVisible = visible
        }
    }

    /**
     * Layer Management APIs
     */

    fun getLayers(): List<LayerMetadata> = layersList.toList()

    fun getActiveLayerId(): String? = internalActiveLayerId

    fun setLayers(layers: List<LayerMetadata>, activeId: String? = null) {
        if (layers.isEmpty()) return
        layersList.clear()
        layersList.addAll(layers)
        internalActiveLayerId = if (activeId != null && layers.any { it.id == activeId }) {
            activeId
        } else {
            layers.first().id
        }

        if (rendererInstance != null) {
            queueEvent {
                val r = rendererInstance ?: return@queueEvent
                for (l in r.layers) {
                    l.release()
                }
                r.layers.clear()
                for (meta in layersList) {
                    val cl = CanvasLayer(
                        id = meta.id,
                        name = meta.name,
                        opacity = meta.opacity,
                        isVisible = meta.isVisible,
                        fbo = GlesFbo(canvasWidth, canvasHeight)
                    )
                    r.clearLayer(cl, 0.0f, 0.0f, 0.0f, 0.0f)
                    r.layers.add(cl)
                }
                internalActiveLayerId?.let { r.selectLayer(it) }
                requestRender()
            }
        }
        onLayersChanged?.invoke()
    }

    fun addLayer(name: String? = null): LayerMetadata {
        val layerNum = layersList.size + 1
        val layerName = name ?: "Layer $layerNum"
        val newLayer = LayerMetadata(
            id = java.util.UUID.randomUUID().toString(),
            name = layerName,
            opacity = 1.0f,
            isVisible = true
        )
        val activeIdx = layersList.indexOfFirst { it.id == internalActiveLayerId }
        val insertIdx = if (activeIdx >= 0) activeIdx + 1 else layersList.size
        layersList.add(insertIdx, newLayer)
        internalActiveLayerId = newLayer.id

        if (rendererInstance != null) {
            queueEvent {
                rendererInstance?.addLayer(newLayer.name, newLayer.id)
                requestRender()
            }
        }
        onLayersChanged?.invoke()
        return newLayer
    }

    fun selectLayer(id: String): Boolean {
        val exists = layersList.any { it.id == id }
        if (!exists) return false
        internalActiveLayerId = id
        if (rendererInstance != null) {
            queueEvent {
                rendererInstance?.selectLayer(id)
                requestRender()
            }
        }
        onLayersChanged?.invoke()
        return true
    }

    fun deleteLayer(id: String): Boolean {
        if (layersList.size <= 1) return false
        val idx = layersList.indexOfFirst { it.id == id }
        if (idx < 0) return false
        layersList.removeAt(idx)
        if (internalActiveLayerId == id) {
            internalActiveLayerId = layersList[idx.coerceAtMost(layersList.size - 1)].id
        }
        if (rendererInstance != null) {
            queueEvent {
                rendererInstance?.deleteLayer(id)
                requestRender()
            }
        }
        onLayersChanged?.invoke()
        return true
    }

    fun moveLayer(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex !in layersList.indices || toIndex !in layersList.indices || fromIndex == toIndex) {
            return false
        }
        val activeId = internalActiveLayerId
        val item = layersList.removeAt(fromIndex)
        layersList.add(toIndex, item)
        internalActiveLayerId = activeId

        if (rendererInstance != null) {
            queueEvent {
                rendererInstance?.moveLayer(fromIndex, toIndex)
                requestRender()
            }
        }
        onLayersChanged?.invoke()
        return true
    }

    fun moveLayerUp(id: String): Boolean {
        val idx = layersList.indexOfFirst { it.id == id }
        if (idx < 0 || idx >= layersList.size - 1) return false
        return moveLayer(idx, idx + 1)
    }

    fun moveLayerDown(id: String): Boolean {
        val idx = layersList.indexOfFirst { it.id == id }
        if (idx <= 0) return false
        return moveLayer(idx, idx - 1)
    }

    fun duplicateLayer(id: String): LayerMetadata? {
        val idx = layersList.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val src = layersList[idx]
        val dup = LayerMetadata(
            id = java.util.UUID.randomUUID().toString(),
            name = "${src.name} Copy",
            opacity = src.opacity,
            isVisible = src.isVisible
        )
        layersList.add(idx + 1, dup)
        internalActiveLayerId = dup.id

        if (rendererInstance != null) {
            queueEvent {
                rendererInstance?.duplicateLayer(id, dup.id, dup.name)
                requestRender()
            }
        }
        onLayersChanged?.invoke()
        return dup
    }

    fun mergeDown(id: String): LayerMetadata? {
        val idx = layersList.indexOfFirst { it.id == id }
        if (idx <= 0) return null
        layersList.removeAt(idx)
        val lower = layersList[idx - 1]
        internalActiveLayerId = lower.id

        if (rendererInstance != null) {
            queueEvent {
                rendererInstance?.mergeDown(id)
                requestRender()
            }
        }
        onLayersChanged?.invoke()
        return lower
    }

    fun setLayerOpacity(id: String, opacity: Float): Boolean {
        val layer = layersList.find { it.id == id } ?: return false
        layer.opacity = opacity.coerceIn(0f, 1f)
        if (rendererInstance != null) {
            queueEvent {
                rendererInstance?.setLayerOpacity(id, opacity)
                requestRender()
            }
        }
        onLayersChanged?.invoke()
        return true
    }

    fun setLayerVisibility(id: String, isVisible: Boolean): Boolean {
        val layer = layersList.find { it.id == id } ?: return false
        layer.isVisible = isVisible
        if (rendererInstance != null) {
            queueEvent {
                rendererInstance?.setLayerVisibility(id, isVisible)
                requestRender()
            }
        }
        onLayersChanged?.invoke()
        return true
    }

    /**
     * Reinitialize the canvas dimensions to match a new aspect ratio and resolution.
     * Reallocates FBOs, clears to white, resets viewport scale and centers the canvas.
     */
    fun setCanvasDimensions(width: Int, height: Int) {
        canvasWidth = width
        canvasHeight = height
        if (rendererInstance != null) {
            queueEvent {
                rendererInstance?.resizeCanvas(width, height)
                requestRender()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            resetView()
        } else {
            post { resetView() }
        }
    }

    fun sampleCanvasColor(canvasX: Float, canvasY: Float): Int {
        val r = rendererInstance ?: return brushColor
        var result = brushColor
        val latch = java.util.concurrent.CountDownLatch(1)
        queueEvent {
            try {
                result = r.sampleCanvasColor(canvasX, canvasY)
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return result
    }

    private var lastSampledCanvasX: Float = Float.NaN
    private var lastSampledCanvasY: Float = Float.NaN
    private var lastSampleTimeMs: Long = 0L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEyedropperActive) {
            val (cx, cy) = screenToCanvas(event.x, event.y)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastSampledCanvasX = cx
                    lastSampledCanvasY = cy
                    lastSampleTimeMs = android.os.SystemClock.uptimeMillis()
                    val sampled = sampleCanvasColor(cx, cy)
                    brushColor = sampled
                }
                MotionEvent.ACTION_MOVE -> {
                    val now = android.os.SystemClock.uptimeMillis()
                    val dx = cx - lastSampledCanvasX
                    val dy = cy - lastSampledCanvasY
                    val distSq = dx * dx + dy * dy
                    // Throttle: only read pixel if touch moves >= 4 canvas pixels (16 px^2) or >= 30ms elapsed
                    if (distSq >= 16f || (now - lastSampleTimeMs) >= 30L) {
                        lastSampledCanvasX = cx
                        lastSampledCanvasY = cy
                        lastSampleTimeMs = now
                        val sampled = sampleCanvasColor(cx, cy)
                        // Live visual feedback during drag, without firing full confirmation notification
                        brushColor = sampled
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val sampled = sampleCanvasColor(cx, cy)
                    brushColor = sampled
                    isEyedropperActive = false
                    lastSampledCanvasX = Float.NaN
                    lastSampledCanvasY = Float.NaN
                    lastSampleTimeMs = 0L
                    onColorSampled?.invoke(sampled)
                }
                MotionEvent.ACTION_CANCEL -> {
                    isEyedropperActive = false
                    lastSampledCanvasX = Float.NaN
                    lastSampledCanvasY = Float.NaN
                    lastSampleTimeMs = 0L
                }
            }
            return true
        }

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

                            // Two-Finger Midpoint-Pivoted Affine Gesture Update:
                            // To rotate and zoom about the dynamic gesture focal point F without canvas drift:
                            // T_new = F_curr + R(deltaTheta) * (s_actual * (T_prev - F_prev))
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

    fun loadBaseBitmap(bitmap: Bitmap) {
        if (rendererInstance != null) {
            queueEvent {
                rendererInstance?.loadBaseBitmap(bitmap)
                bitmap.recycle()
                requestRender()
            }
        } else {
            pendingBaseBitmap = bitmap
        }
    }

    fun exportBaseBitmap(onExported: (Bitmap) -> Unit) {
        queueEvent {
            val bmp = rendererInstance?.exportBaseBitmap()
            if (bmp != null) {
                post { onExported(bmp) }
            }
        }
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
