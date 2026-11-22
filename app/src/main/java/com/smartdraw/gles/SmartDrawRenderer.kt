package com.smartdraw.gles

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
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

    val referenceLayer = ReferenceLayer()

    private val brushShader = CircleBrushShader()
    private val smudgeShader = SmudgeShader()
    private val compositingShader = CompositingShader()

    var solidBackgroundColor = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f) // White by default

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
    }

    fun loadBaseBitmap(bitmap: Bitmap) {
        baseFbo.loadBitmap(bitmap)
        blitFbo(baseFbo, scratchFbo)
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

        baseFbo.bind()
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_SRC_ALPHA,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA
        )

        var lastR = Color.red(defaultColor) / 255f
        var lastG = Color.green(defaultColor) / 255f
        var lastB = Color.blue(defaultColor) / 255f
        var lastA = Color.alpha(defaultColor) / 255f

        for (pt in samples) {
            if (useReferenceColors) {
                val c = referenceLayer.sampleColor(pt.x, pt.y, width.toFloat(), height.toFloat(), defaultColor)
                lastR = Color.red(c) / 255f
                lastG = Color.green(c) / 255f
                lastB = Color.blue(c) / 255f
                lastA = Color.alpha(c) / 255f
            }

            brushShader.renderStamp(
                canvasWidth = width.toFloat(),
                canvasHeight = height.toFloat(),
                centerX = pt.x,
                centerY = pt.y,
                radius = baseRadius,
                colorR = lastR,
                colorG = lastG,
                colorB = lastB,
                colorA = lastA,
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

    fun composite(targetFramebuffer: Int = compositeFbo.framebufferId, viewportW: Int = width, viewportH: Int = height) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFramebuffer)
        GLES30.glViewport(0, 0, viewportW, viewportH)
        GLES30.glDisable(GLES30.GL_BLEND)

        compositingShader.renderComposite(
            baseTextureId = baseFbo.textureId,
            referenceTextureId = referenceLayer.textureId,
            referenceOpacity = referenceLayer.opacity,
            renderReference = referenceLayer.isVisible && referenceLayer.textureId != 0,
            backgroundColor = solidBackgroundColor
        )

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun exportBaseBitmap(): Bitmap = baseFbo.readBitmap()

    fun exportCompositeBitmap(): Bitmap {
        composite(compositeFbo.framebufferId, width, height)
        return compositeFbo.readBitmap()
    }

    fun release() {
        baseFbo.release()
        scratchFbo.release()
        compositeFbo.release()
        referenceLayer.release()
        brushShader.release()
        smudgeShader.release()
        compositingShader.release()
    }
}
