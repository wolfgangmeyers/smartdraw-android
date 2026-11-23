package com.smartdraw.gles

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.Matrix
import com.smartdraw.SmartDrawView
import com.smartdraw.core.BezierCurve
import com.smartdraw.core.Point2D
import com.smartdraw.core.RatchetEvaluator
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max

class SmartDrawRenderer(
    var width: Int,
    var height: Int
) {
    val layers = ArrayList<CanvasLayer>()
    var activeLayerIndex: Int = 0

    val baseFbo: GlesFbo
        get() = getActiveLayer().fbo

    var scratchFbo = GlesFbo(width, height)
        private set
    var compositeFbo = GlesFbo(width, height)
        private set
    var activeStrokeFbo = GlesFbo(width, height)
        private set

    var hasActiveStroke: Boolean = false
    var isRatchetEnabled: Boolean = false

    val referenceLayer = ReferenceLayer()

    private val brushShader = CircleBrushShader()
    private val smudgeShader = SmudgeShader()
    private val compositingShader = CompositingShader()

    var solidBackgroundColor = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f) // White by default

    data class ActiveStrokeSegment(
        val curve: BezierCurve,
        val radius: Float,
        val opacity: Float,
        val toolMode: SmartDrawView.ToolMode = SmartDrawView.ToolMode.PAINTBRUSH
    )

    internal val activeStrokePoints = ArrayList<Point2D>()
    internal val activeStrokeRadii = ArrayList<Float>()
    internal val activeStrokeSegments = ArrayList<ActiveStrokeSegment>()

    init {
        val initialLayer = CanvasLayer(
            name = "Layer 1",
            fbo = GlesFbo(width, height)
        )
        clearLayer(initialLayer, 0.0f, 0.0f, 0.0f, 0.0f)
        layers.add(initialLayer)
        activeLayerIndex = 0

        clear(0.0f, 0.0f, 0.0f, 0.0f)
    }

    fun getActiveLayer(): CanvasLayer {
        if (layers.isEmpty()) {
            val l = CanvasLayer(name = "Layer 1", fbo = GlesFbo(width, height))
            clearLayer(l, 0.0f, 0.0f, 0.0f, 0.0f)
            layers.add(l)
            activeLayerIndex = 0
            return l
        }
        if (activeLayerIndex !in layers.indices) {
            activeLayerIndex = activeLayerIndex.coerceIn(0, layers.size - 1)
        }
        return layers[activeLayerIndex]
    }

    var activeLayerId: String
        get() = getActiveLayer().id
        set(value) {
            val idx = layers.indexOfFirst { it.id == value }
            if (idx >= 0) activeLayerIndex = idx
        }

    fun clearLayer(layer: CanvasLayer, r: Float = 0.0f, g: Float = 0.0f, b: Float = 0.0f, a: Float = 0.0f) {
        layer.fbo.bind()
        GLES30.glClearColor(r, g, b, a)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        layer.fbo.unbind()
    }

    fun clear(r: Float = 0.0f, g: Float = 0.0f, b: Float = 0.0f, a: Float = 0.0f) {
        for (layer in layers) {
            clearLayer(layer, r, g, b, a)
        }

        scratchFbo.bind()
        GLES30.glClearColor(r, g, b, a)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        scratchFbo.unbind()

        clearActiveStroke()
    }

    fun clearCanvas(r: Float = 1.0f, g: Float = 1.0f, b: Float = 1.0f, a: Float = 1.0f) {
        solidBackgroundColor = floatArrayOf(r, g, b, a)
        clear(0.0f, 0.0f, 0.0f, 0.0f)
    }

    /**
     * Layer Management APIs
     */

    fun addLayer(name: String? = null, id: String = UUID.randomUUID().toString()): CanvasLayer {
        val layerNumber = layers.size + 1
        val layerName = name ?: "Layer $layerNumber"
        val newLayer = CanvasLayer(
            id = id,
            name = layerName,
            opacity = 1.0f,
            isVisible = true,
            fbo = GlesFbo(width, height)
        )
        clearLayer(newLayer, 0.0f, 0.0f, 0.0f, 0.0f)
        val insertIndex = (activeLayerIndex + 1).coerceIn(0, layers.size)
        layers.add(insertIndex, newLayer)
        activeLayerIndex = insertIndex
        return newLayer
    }

    fun selectLayer(id: String): Boolean {
        val idx = layers.indexOfFirst { it.id == id }
        if (idx >= 0) {
            activeLayerIndex = idx
            return true
        }
        return false
    }

    fun selectLayer(index: Int): Boolean {
        if (index in layers.indices) {
            activeLayerIndex = index
            return true
        }
        return false
    }

    fun deleteLayer(id: String): Boolean {
        if (layers.size <= 1) return false
        val idx = layers.indexOfFirst { it.id == id }
        if (idx < 0) return false
        return deleteLayer(idx)
    }

    fun deleteLayer(index: Int): Boolean {
        if (layers.size <= 1 || index !in layers.indices) return false
        val layer = layers.removeAt(index)
        layer.release()
        activeLayerIndex = activeLayerIndex.coerceIn(0, layers.size - 1)
        return true
    }

    fun moveLayer(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex !in layers.indices || toIndex !in layers.indices || fromIndex == toIndex) {
            return false
        }
        val active = getActiveLayer()
        val layer = layers.removeAt(fromIndex)
        layers.add(toIndex, layer)
        activeLayerIndex = layers.indexOf(active).coerceAtLeast(0)
        return true
    }

    fun moveLayerUp(id: String): Boolean {
        val idx = layers.indexOfFirst { it.id == id }
        if (idx < 0 || idx >= layers.size - 1) return false
        return moveLayer(idx, idx + 1)
    }

    fun moveLayerDown(id: String): Boolean {
        val idx = layers.indexOfFirst { it.id == id }
        if (idx <= 0) return false
        return moveLayer(idx, idx - 1)
    }

    fun duplicateLayer(id: String, newId: String = UUID.randomUUID().toString(), newName: String? = null): CanvasLayer? {
        val idx = layers.indexOfFirst { it.id == id }
        if (idx < 0) return null
        return duplicateLayer(idx, newId, newName)
    }

    fun duplicateLayer(index: Int, newId: String = UUID.randomUUID().toString(), newName: String? = null): CanvasLayer? {
        if (index !in layers.indices) return null
        val srcLayer = layers[index]
        val dupLayer = CanvasLayer(
            id = newId,
            name = newName ?: "${srcLayer.name} Copy",
            opacity = srcLayer.opacity,
            isVisible = srcLayer.isVisible,
            fbo = GlesFbo(width, height)
        )
        blitFbo(srcLayer.fbo, dupLayer.fbo)
        val insertIdx = index + 1
        layers.add(insertIdx, dupLayer)
        activeLayerIndex = insertIdx
        return dupLayer
    }

    fun mergeDown(id: String): CanvasLayer? {
        val idx = layers.indexOfFirst { it.id == id }
        if (idx <= 0) return null
        return mergeDown(idx)
    }

    fun mergeDown(index: Int): CanvasLayer? {
        if (index <= 0 || index !in layers.indices) return null
        val upperLayer = layers[index]
        val lowerLayer = layers[index - 1]

        lowerLayer.fbo.bind()
        GLES30.glViewport(0, 0, width, height)

        val orthoM = FloatArray(16)
        Matrix.orthoM(orthoM, 0, 0f, width.toFloat(), height.toFloat(), 0f, -1.0f, 1.0f)
        val modelM = FloatArray(16)
        Matrix.setIdentityM(modelM, 0)
        Matrix.scaleM(modelM, 0, width.toFloat(), height.toFloat(), 1.0f)
        val fboMvp = FloatArray(16)
        Matrix.multiplyMM(fboMvp, 0, orthoM, 0, modelM, 0)

        compositingShader.renderLayerQuad(
            textureId = upperLayer.fbo.textureId,
            opacity = upperLayer.opacity,
            mvpMatrix = fboMvp
        )
        lowerLayer.fbo.unbind()

        layers.removeAt(index)
        upperLayer.release()
        activeLayerIndex = (index - 1).coerceAtLeast(0)
        return lowerLayer
    }

    fun setLayerOpacity(id: String, opacity: Float): Boolean {
        val layer = layers.find { it.id == id } ?: return false
        layer.opacity = opacity.coerceIn(0.0f, 1.0f)
        return true
    }

    fun setLayerOpacity(index: Int, opacity: Float): Boolean {
        if (index !in layers.indices) return false
        layers[index].opacity = opacity.coerceIn(0.0f, 1.0f)
        return true
    }

    fun setLayerVisibility(id: String, isVisible: Boolean): Boolean {
        val layer = layers.find { it.id == id } ?: return false
        layer.isVisible = isVisible
        return true
    }

    fun setLayerVisibility(index: Int, isVisible: Boolean): Boolean {
        if (index !in layers.indices) return false
        layers[index].isVisible = isVisible
        return true
    }

    /**
     * Dynamically resize the drawing canvas and reallocate FBOs.
     * Initializes base and scratch FBOs with an opaque white background.
     */
    fun resizeCanvas(newWidth: Int, newHeight: Int) {
        if (width == newWidth && height == newHeight) {
            clear(0.0f, 0.0f, 0.0f, 0.0f)
            return
        }

        width = newWidth
        height = newHeight

        for (layer in layers) {
            layer.release()
            layer.fbo = GlesFbo(width, height)
        }
        scratchFbo.release()
        compositeFbo.release()
        activeStrokeFbo.release()

        scratchFbo = GlesFbo(width, height)
        compositeFbo = GlesFbo(width, height)
        activeStrokeFbo = GlesFbo(width, height)

        clear(0.0f, 0.0f, 0.0f, 0.0f)
    }

    fun loadBaseBitmap(bitmap: Bitmap) {
        val active = getActiveLayer()
        active.fbo.loadBitmap(bitmap)
        blitFbo(active.fbo, scratchFbo)
    }

    fun beginActiveStroke(previewColor: Int) {
        activeStrokePoints.clear()
        activeStrokeRadii.clear()
        activeStrokeSegments.clear()

        val r = Color.red(previewColor) / 255f
        val g = Color.green(previewColor) / 255f
        val b = Color.blue(previewColor) / 255f

        activeStrokeFbo.bind()
        GLES30.glClearColor(r, g, b, 0.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        activeStrokeFbo.unbind()

        hasActiveStroke = true
    }

    fun drawActiveStrokeSegment(
        curve: BezierCurve,
        radius: Float,
        opacity: Float = 1.0f,
        previewColor: Int,
        spacingRatio: Float = 0.2f,
        toolMode: SmartDrawView.ToolMode = SmartDrawView.ToolMode.PAINTBRUSH
    ) {
        if (!hasActiveStroke) {
            beginActiveStroke(previewColor)
        }

        activeStrokeSegments.add(ActiveStrokeSegment(curve, radius, opacity, toolMode))

        val stepSize = max(0.25f, radius * spacingRatio)
        val samples = curve.sampleUniform(stepSize)

        val r = Color.red(previewColor) / 255f
        val g = Color.green(previewColor) / 255f
        val b = Color.blue(previewColor) / 255f
        val baseAlpha = (Color.alpha(previewColor) / 255f) * opacity

        activeStrokeFbo.bind()
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_SRC_ALPHA,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA
        )

        for (pt in samples) {
            val stampRadius: Float
            val stampPressure: Float
            val stampAlpha: Float

            when (toolMode) {
                SmartDrawView.ToolMode.PEN -> {
                    stampRadius = radius
                    stampPressure = 1.0f
                    stampAlpha = baseAlpha
                    activeStrokeRadii.add(radius)
                }
                SmartDrawView.ToolMode.PAINTBRUSH,
                SmartDrawView.ToolMode.SMUDGE -> {
                    stampRadius = radius
                    stampPressure = pt.pressure
                    stampAlpha = baseAlpha
                    activeStrokeRadii.add(radius * pt.pressure)
                }
            }

            activeStrokePoints.add(pt)

            brushShader.renderStamp(
                canvasWidth = width.toFloat(),
                canvasHeight = height.toFloat(),
                centerX = pt.x,
                centerY = pt.y,
                radius = stampRadius,
                colorR = r,
                colorG = g,
                colorB = b,
                colorA = stampAlpha,
                pressure = stampPressure
            )
        }

        GLES30.glDisable(GLES30.GL_BLEND)
        activeStrokeFbo.unbind()
    }

    fun endActiveStroke(
        useReferenceColors: Boolean,
        defaultColor: Int,
        spacingRatio: Float = 0.2f
    ): Boolean {
        if (activeStrokeSegments.isEmpty() && activeStrokePoints.isEmpty()) {
            clearActiveStroke()
            return true
        }

        // 1. Calculate final color by averaging reference pixels covered by the stroke
        val finalColor = if (useReferenceColors) {
            referenceLayer.computeAverageCoveredColor(
                points = activeStrokePoints,
                radii = activeStrokeRadii,
                canvasWidth = width.toFloat(),
                canvasHeight = height.toFloat(),
                defaultColor = defaultColor
            )
        } else {
            defaultColor
        }

        // 2. Ratchet Mode evaluation if enabled and a reference image is loaded
        val refBmp = referenceLayer.bitmap
        if (isRatchetEnabled && referenceLayer.hasImage && refBmp != null && !refBmp.isRecycled) {
            var maxSegR = 0f
            for (seg in activeStrokeSegments) {
                if (seg.radius > maxSegR) maxSegR = seg.radius
            }

            val box = RatchetEvaluator.computeBoundingBox(
                points = activeStrokePoints,
                radii = activeStrokeRadii,
                maxSegmentRadius = maxSegR,
                canvasWidth = width,
                canvasHeight = height
            )

            if (box.width > 0 && box.height > 0) {
                // Read baseFbo sub-rect
                val basePixels = baseFbo.readSubRect(box.x0, box.y0, box.width, box.height)

                // Rasterize stroke into candidate copy of sub-rect
                val candidatePixels = basePixels.copyOf()
                val segmentTriples = activeStrokeSegments.map { Triple(it.curve, it.radius, it.opacity) }
                RatchetEvaluator.simulateStrokeOnSubRect(
                    subRectPixels = candidatePixels,
                    box = box,
                    segments = segmentTriples,
                    finalColor = finalColor,
                    spacingRatio = spacingRatio,
                    toolModes = activeStrokeSegments.map { it.toolMode }
                )

                val refW = refBmp.width
                val refH = refBmp.height
                val refPixels = IntArray(refW * refH)
                refBmp.getPixels(refPixels, 0, refW, 0, 0, refW, refH)

                val keepStroke = RatchetEvaluator.evaluateSimilarity(
                    basePixels = basePixels,
                    candidatePixels = candidatePixels,
                    box = box,
                    refPixels = refPixels,
                    refWidth = refW,
                    refHeight = refH,
                    canvasWidth = width,
                    canvasHeight = height
                )

                if (!keepStroke) {
                    // Stroke worsened similarity! Discard and rollback.
                    clearActiveStroke()
                    return false
                }
            }
        }

        // 3. Commit all accumulated segments of this stroke to baseFbo using the final average color
        baseFbo.bind()
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_SRC_ALPHA,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA
        )

        val r = Color.red(finalColor) / 255f
        val g = Color.green(finalColor) / 255f
        val b = Color.blue(finalColor) / 255f
        val baseAlpha = Color.alpha(finalColor) / 255f

        for (seg in activeStrokeSegments) {
            val stepSize = max(0.25f, seg.radius * spacingRatio)
            val samples = seg.curve.sampleUniform(stepSize)
            val baseSegAlpha = baseAlpha * seg.opacity

            for (pt in samples) {
                val stampRadius: Float
                val stampPressure: Float
                val stampAlpha: Float

                when (seg.toolMode) {
                    SmartDrawView.ToolMode.PEN -> {
                        stampRadius = seg.radius
                        stampPressure = 1.0f
                        stampAlpha = baseSegAlpha
                    }
                    SmartDrawView.ToolMode.PAINTBRUSH,
                    SmartDrawView.ToolMode.SMUDGE -> {
                        stampRadius = seg.radius
                        stampPressure = pt.pressure
                        stampAlpha = baseSegAlpha
                    }
                }

                brushShader.renderStamp(
                    canvasWidth = width.toFloat(),
                    canvasHeight = height.toFloat(),
                    centerX = pt.x,
                    centerY = pt.y,
                    radius = stampRadius,
                    colorR = r,
                    colorG = g,
                    colorB = b,
                    colorA = stampAlpha,
                    pressure = stampPressure
                )
            }
        }

        GLES30.glDisable(GLES30.GL_BLEND)
        baseFbo.unbind()

        // 4. Clear active stroke buffer now that baseFbo has received the permanent stroke
        clearActiveStroke()

        return true
    }

    fun clearActiveStroke() {
        activeStrokeFbo.bind()
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        activeStrokeFbo.unbind()

        hasActiveStroke = false
        activeStrokePoints.clear()
        activeStrokeRadii.clear()
        activeStrokeSegments.clear()
    }

    fun drawPoint(
        x: Float,
        y: Float,
        radius: Float,
        colorR: Float,
        colorG: Float,
        colorB: Float,
        colorA: Float,
        pressure: Float = 1.0f
    ) {
        baseFbo.bind()
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_SRC_ALPHA,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA
        )

        brushShader.renderStamp(
            canvasWidth = width.toFloat(),
            canvasHeight = height.toFloat(),
            centerX = x,
            centerY = y,
            radius = radius,
            colorR = colorR,
            colorG = colorG,
            colorB = colorB,
            colorA = colorA,
            pressure = pressure
        )

        GLES30.glDisable(GLES30.GL_BLEND)
        baseFbo.unbind()
    }

    fun drawLine(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radius: Float,
        colorR: Float,
        colorG: Float,
        colorB: Float,
        colorA: Float,
        p1: Float = 1.0f,
        p2: Float = 1.0f,
        spacingRatio: Float = 0.25f
    ) {
        val dist = hypot(x2 - x1, y2 - y1)
        val step = max(0.25f, radius * spacingRatio)
        val steps = max(1, (dist / step).toInt())

        baseFbo.bind()
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_SRC_ALPHA,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA
        )

        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = x1 + (x2 - x1) * t
            val y = y1 + (y2 - y1) * t
            val p = p1 + (p2 - p1) * t

            brushShader.renderStamp(
                canvasWidth = width.toFloat(),
                canvasHeight = height.toFloat(),
                centerX = x,
                centerY = y,
                radius = radius,
                colorR = colorR,
                colorG = colorG,
                colorB = colorB,
                colorA = colorA,
                pressure = p
            )
        }

        GLES30.glDisable(GLES30.GL_BLEND)
        baseFbo.unbind()
    }

    fun drawBezierStroke(
        curve: BezierCurve,
        baseRadius: Float,
        defaultColor: Int = Color.BLACK,
        useReferenceColors: Boolean = false,
        spacingRatio: Float = 0.25f
    ) {
        val stepSize = max(0.25f, baseRadius * spacingRatio)
        val samples = curve.sampleUniform(stepSize)

        val strokeColor = if (useReferenceColors) {
            val radii = List(samples.size) { baseRadius }
            referenceLayer.computeAverageCoveredColor(
                points = samples,
                radii = radii,
                canvasWidth = width.toFloat(),
                canvasHeight = height.toFloat(),
                defaultColor = defaultColor
            )
        } else {
            defaultColor
        }

        val r = Color.red(strokeColor) / 255f
        val g = Color.green(strokeColor) / 255f
        val b = Color.blue(strokeColor) / 255f
        val a = Color.alpha(strokeColor) / 255f

        baseFbo.bind()
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_SRC_ALPHA,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA
        )

        for (pt in samples) {
            brushShader.renderStamp(
                canvasWidth = width.toFloat(),
                canvasHeight = height.toFloat(),
                centerX = pt.x,
                centerY = pt.y,
                radius = baseRadius,
                colorR = r,
                colorG = g,
                colorB = b,
                colorA = a,
                pressure = pt.pressure
            )
        }

        GLES30.glDisable(GLES30.GL_BLEND)
        baseFbo.unbind()
    }

    private fun blitFbo(src: GlesFbo, dst: GlesFbo) {
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, src.framebufferId)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, dst.framebufferId)
        GLES30.glBlitFramebuffer(
            0, 0, width, height,
            0, 0, width, height,
            GLES30.GL_COLOR_BUFFER_BIT,
            GLES30.GL_NEAREST
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun smudgePoint(
        x: Float,
        y: Float,
        radius: Float,
        blurStrength: Float = 2.0f,
        opacity: Float = 0.5f
    ) {
        // Copy base into scratch so scratch is the pristine source
        blitFbo(baseFbo, scratchFbo)

        baseFbo.bind()
        // Smudge replaces fragment color
        GLES30.glDisable(GLES30.GL_BLEND)

        smudgeShader.renderSmudgeStamp(
            canvasWidth = width.toFloat(),
            canvasHeight = height.toFloat(),
            centerX = x,
            centerY = y,
            radius = radius,
            blurStrength = blurStrength,
            opacity = opacity,
            sourceTextureId = scratchFbo.textureId
        )

        baseFbo.unbind()
    }

    fun smudgeLine(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radius: Float,
        blurStrength: Float = 2.0f,
        opacity: Float = 0.5f,
        spacingRatio: Float = 0.25f
    ) {
        val dist = hypot(x2 - x1, y2 - y1)
        val step = max(0.5f, radius * spacingRatio)
        val steps = max(1, (dist / step).toInt())

        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = x1 + (x2 - x1) * t
            val y = y1 + (y2 - y1) * t
            smudgePoint(x, y, radius, blurStrength, opacity)
        }
    }

    fun smudgeBezier(
        curve: BezierCurve,
        radius: Float,
        blurStrength: Float = 2.0f,
        opacity: Float = 0.5f,
        spacingRatio: Float = 0.25f
    ) {
        val stepSize = max(0.5f, radius * spacingRatio)
        val samples = curve.sampleUniform(stepSize)
        for (pt in samples) {
            smudgePoint(pt.x, pt.y, radius, blurStrength, opacity)
        }
    }

    fun composite(
        targetFramebuffer: Int = compositeFbo.framebufferId,
        viewportW: Int = width,
        viewportH: Int = height,
        translationX: Float = 0f,
        translationY: Float = 0f,
        scale: Float = 1.0f,
        rotationDegrees: Float = 0f
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFramebuffer)
        GLES30.glViewport(0, 0, viewportW, viewportH)

        // Clear target framebuffer to neutral dark background (#1E1E1E) when drawing to screen (or if transformed)
        GLES30.glClearColor(0.1176f, 0.1176f, 0.1176f, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // Screen orthographic projection: left=0, right=viewportW, bottom=viewportH, top=0
        val projMatrix = FloatArray(16)
        Matrix.orthoM(projMatrix, 0, 0f, viewportW.toFloat(), viewportH.toFloat(), 0f, -1.0f, 1.0f)

        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, translationX, translationY, 0f)
        Matrix.rotateM(modelMatrix, 0, rotationDegrees, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, scale * width.toFloat(), scale * height.toFloat(), 1.0f)

        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, modelMatrix, 0)

        // 1. Render solid canvas background
        compositingShader.renderSolidQuad(solidBackgroundColor, mvpMatrix)

        // 2. Render all canvas layers bottom-to-top using compositingShader.renderLayerQuad
        val activeLayer = getActiveLayer()
        val hasActive = hasActiveStroke && activeStrokeFbo.textureId != 0
        for (layer in layers) {
            if (!layer.isVisible || layer.opacity <= 0.001f) continue
            compositingShader.renderLayerQuad(
                textureId = layer.fbo.textureId,
                opacity = layer.opacity,
                mvpMatrix = mvpMatrix
            )
            // Render active stroke on top of the active layer
            if (layer == activeLayer && hasActive) {
                compositingShader.renderLayerQuad(
                    textureId = activeStrokeFbo.textureId,
                    opacity = layer.opacity,
                    mvpMatrix = mvpMatrix
                )
            }
        }

        // 3. Render reference layer overlay if enabled
        if (referenceLayer.isVisible && referenceLayer.textureId != 0 && referenceLayer.opacity > 0.001f) {
            compositingShader.renderLayerQuad(
                textureId = referenceLayer.textureId,
                opacity = referenceLayer.opacity,
                mvpMatrix = mvpMatrix
            )
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun renderToScreen(
        viewportW: Int = width,
        viewportH: Int = height,
        translationX: Float = 0f,
        translationY: Float = 0f,
        scale: Float = 1.0f,
        rotationDegrees: Float = 0f
    ) {
        composite(
            targetFramebuffer = 0,
            viewportW = viewportW,
            viewportH = viewportH,
            translationX = translationX,
            translationY = translationY,
            scale = scale,
            rotationDegrees = rotationDegrees
        )
    }

    fun exportBaseBitmap(): Bitmap = baseFbo.readBitmap()

    fun sampleCanvasColor(canvasX: Float, canvasY: Float): Int {
        if (width <= 0 || height <= 0) return Color.BLACK
        val x = canvasX.toInt().coerceIn(0, width - 1)
        val y = canvasY.toInt().coerceIn(0, height - 1)
        val glY = (height - 1 - y).coerceIn(0, height - 1)

        baseFbo.bind()
        val buffer = java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder())
        GLES30.glReadPixels(x, glY, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        baseFbo.unbind()

        buffer.rewind()
        val r = buffer.get().toInt() and 0xFF
        val g = buffer.get().toInt() and 0xFF
        val b = buffer.get().toInt() and 0xFF
        val a = buffer.get().toInt() and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun exportCompositeBitmap(): Bitmap {
        composite(
            targetFramebuffer = compositeFbo.framebufferId,
            viewportW = width,
            viewportH = height,
            translationX = 0f,
            translationY = 0f,
            scale = 1.0f,
            rotationDegrees = 0f
        )
        return compositeFbo.readBitmap()
    }

    fun release() {
        for (layer in layers) {
            layer.release()
        }
        layers.clear()
        scratchFbo.release()
        compositeFbo.release()
        activeStrokeFbo.release()
        referenceLayer.release()
        brushShader.release()
        smudgeShader.release()
        compositingShader.release()
    }
}
