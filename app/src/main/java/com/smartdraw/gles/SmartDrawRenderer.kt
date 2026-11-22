package com.smartdraw.gles

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.Matrix
import com.smartdraw.core.BezierCurve
import com.smartdraw.core.Point2D
import kotlin.math.hypot
import kotlin.math.max

class SmartDrawRenderer(
    val width: Int,
    val height: Int
) {
    val baseFbo = GlesFbo(width, height)
    val scratchFbo = GlesFbo(width, height)
    val compositeFbo = GlesFbo(width, height)
    val activeStrokeFbo = GlesFbo(width, height)

    var hasActiveStroke: Boolean = false

    val referenceLayer = ReferenceLayer()

    private val brushShader = CircleBrushShader()
    private val smudgeShader = SmudgeShader()
    private val compositingShader = CompositingShader()

    var solidBackgroundColor = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f) // White by default

    data class ActiveStrokeSegment(
        val curve: BezierCurve,
        val radius: Float,
        val opacity: Float
    )

    private val activeStrokePoints = ArrayList<Point2D>()
    private val activeStrokeRadii = ArrayList<Float>()
    private val activeStrokeSegments = ArrayList<ActiveStrokeSegment>()

    init {
        clear(1.0f, 1.0f, 1.0f, 1.0f) // Opaque white base canvas
    }

    fun clear(r: Float = 1.0f, g: Float = 1.0f, b: Float = 1.0f, a: Float = 1.0f) {
        baseFbo.bind()
        GLES30.glClearColor(r, g, b, a)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        baseFbo.unbind()

        scratchFbo.bind()
        GLES30.glClearColor(r, g, b, a)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        scratchFbo.unbind()

        clearActiveStroke()
    }

    fun clearCanvas(r: Float = 1.0f, g: Float = 1.0f, b: Float = 1.0f, a: Float = 1.0f) {
        clear(r, g, b, a)
    }

    fun loadBaseBitmap(bitmap: Bitmap) {
        baseFbo.loadBitmap(bitmap)
        blitFbo(baseFbo, scratchFbo)
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
        spacingRatio: Float = 0.2f
    ) {
        if (!hasActiveStroke) {
            beginActiveStroke(previewColor)
        }

        activeStrokeSegments.add(ActiveStrokeSegment(curve, radius, opacity))

        val stepSize = max(0.5f, radius * spacingRatio)
        val samples = curve.sampleUniform(stepSize)

        val r = Color.red(previewColor) / 255f
        val g = Color.green(previewColor) / 255f
        val b = Color.blue(previewColor) / 255f
        val a = (Color.alpha(previewColor) / 255f) * opacity

        activeStrokeFbo.bind()
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_SRC_ALPHA,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA
        )

        for (pt in samples) {
            activeStrokePoints.add(pt)
            activeStrokeRadii.add(radius * pt.pressure)

            brushShader.renderStamp(
                canvasWidth = width.toFloat(),
                canvasHeight = height.toFloat(),
                centerX = pt.x,
                centerY = pt.y,
                radius = radius,
                colorR = r,
                colorG = g,
                colorB = b,
                colorA = a,
                pressure = pt.pressure
            )
        }

        GLES30.glDisable(GLES30.GL_BLEND)
        activeStrokeFbo.unbind()
    }

    fun endActiveStroke(
        useReferenceColors: Boolean,
        defaultColor: Int,
        spacingRatio: Float = 0.2f
    ): Int {
        if (activeStrokeSegments.isEmpty() && activeStrokePoints.isEmpty()) {
            clearActiveStroke()
            return defaultColor
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

        // 2. Commit all accumulated segments of this stroke to baseFbo using the final average color
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
            val stepSize = max(0.5f, seg.radius * spacingRatio)
            val samples = seg.curve.sampleUniform(stepSize)
            val a = baseAlpha * seg.opacity

            for (pt in samples) {
                brushShader.renderStamp(
                    canvasWidth = width.toFloat(),
                    canvasHeight = height.toFloat(),
                    centerX = pt.x,
                    centerY = pt.y,
                    radius = seg.radius,
                    colorR = r,
                    colorG = g,
                    colorB = b,
                    colorA = a,
                    pressure = pt.pressure
                )
            }
        }

        GLES30.glDisable(GLES30.GL_BLEND)
        baseFbo.unbind()

        // 3. Clear active stroke buffer now that baseFbo has received the permanent stroke
        clearActiveStroke()

        return finalColor
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
        val step = max(0.5f, radius * spacingRatio)
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
        val stepSize = max(0.5f, baseRadius * spacingRatio)
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
        val step = max(1.0f, radius * spacingRatio)
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
        val stepSize = max(1.0f, radius * spacingRatio)
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

        GLES30.glDisable(GLES30.GL_BLEND)

        val hasActive = hasActiveStroke && activeStrokeFbo.textureId != 0
        compositingShader.renderComposite(
            baseTextureId = baseFbo.textureId,
            referenceTextureId = referenceLayer.textureId,
            referenceOpacity = referenceLayer.opacity,
            renderReference = referenceLayer.isVisible && referenceLayer.textureId != 0,
            activeStrokeTextureId = if (hasActive) activeStrokeFbo.textureId else 0,
            backgroundColor = solidBackgroundColor,
            mvpMatrix = mvpMatrix
        )

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
        baseFbo.release()
        scratchFbo.release()
        compositeFbo.release()
        activeStrokeFbo.release()
        referenceLayer.release()
        brushShader.release()
        smudgeShader.release()
        compositingShader.release()
    }
}
