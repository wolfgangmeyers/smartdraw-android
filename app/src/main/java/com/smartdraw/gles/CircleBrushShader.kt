package com.smartdraw.gles

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class CircleBrushShader {
    private val vertexShaderSource = """#version 300 es
        in vec2 a_position;
        in vec2 a_texCoord;

        uniform vec2 u_resolution;
        uniform vec2 u_brushCenter;
        uniform float u_brushRadius;

        out vec2 v_quadPos;
        out vec2 v_texCoord;

        void main() {
            vec2 worldPos = u_brushCenter + (a_position * u_brushRadius);
            vec2 clipSpace = (worldPos / u_resolution) * 2.0 - 1.0;
            clipSpace.y = -clipSpace.y; // Top-left origin
            gl_Position = vec4(clipSpace, 0.0, 1.0);
            v_quadPos = a_position;
            v_texCoord = a_texCoord;
        }
    """.trimIndent()

    private val fragmentShaderSource = """#version 300 es
        precision highp float;

        in vec2 v_quadPos;
        in vec2 v_texCoord;

        uniform vec4 u_brushColor;
        uniform float u_brushRadius;
        uniform float u_pressure;

        out vec4 fragColor;

        void main() {
            float distInRadius = length(v_quadPos); // 0.0 at center, 1.0 at outer quad edge
            float r_px = distInRadius * u_brushRadius;
            float effectiveRadius = u_brushRadius * clamp(u_pressure, 0.08, 1.0);
            
            // Antialiased circle edge
            float alpha = 1.0 - smoothstep(effectiveRadius - 1.0, effectiveRadius + 1.0, r_px);
            if (alpha <= 0.001) {
                discard;
            }
            // Pressure sensitivity scales both radius and opacity
            float pressureOpacity = clamp(0.15 + 0.85 * u_pressure, 0.08, 1.0);
            fragColor = vec4(u_brushColor.rgb, u_brushColor.a * alpha * pressureOpacity);
        }
    """.trimIndent()

    private val program: Int = GlesShaderUtil.createProgram(vertexShaderSource, fragmentShaderSource)
    private val aPositionLoc: Int = GLES30.glGetAttribLocation(program, "a_position")
    private val aTexCoordLoc: Int = GLES30.glGetAttribLocation(program, "a_texCoord")

    private val uResolutionLoc: Int = GLES30.glGetUniformLocation(program, "u_resolution")
    private val uBrushCenterLoc: Int = GLES30.glGetUniformLocation(program, "u_brushCenter")
    private val uBrushRadiusLoc: Int = GLES30.glGetUniformLocation(program, "u_brushRadius")
    private val uBrushColorLoc: Int = GLES30.glGetUniformLocation(program, "u_brushColor")
    private val uPressureLoc: Int = GLES30.glGetUniformLocation(program, "u_pressure")

    // Quad geometry: two triangles covering [-1, 1] x [-1, 1]
    private val quadBuffer: FloatBuffer

    init {
        val quadVertices = floatArrayOf(
            // pos(x, y),  tex(u, v)
            -1.0f, -1.0f,  0.0f, 0.0f,
             1.0f, -1.0f,  1.0f, 0.0f,
            -1.0f,  1.0f,  0.0f, 1.0f,
            -1.0f,  1.0f,  0.0f, 1.0f,
             1.0f, -1.0f,  1.0f, 0.0f,
             1.0f,  1.0f,  1.0f, 1.0f
        )
        quadBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadVertices)
        quadBuffer.position(0)
    }

    fun renderStamp(
        canvasWidth: Float,
        canvasHeight: Float,
        centerX: Float,
        centerY: Float,
        radius: Float,
        colorR: Float,
        colorG: Float,
        colorB: Float,
        colorA: Float,
        pressure: Float
    ) {
        GLES30.glUseProgram(program)

        GLES30.glUniform2f(uResolutionLoc, canvasWidth, canvasHeight)
        GLES30.glUniform2f(uBrushCenterLoc, centerX, centerY)
        GLES30.glUniform1f(uBrushRadiusLoc, radius)
        GLES30.glUniform4f(uBrushColorLoc, colorR, colorG, colorB, colorA)
        GLES30.glUniform1f(uPressureLoc, pressure)

        quadBuffer.position(0)
        GLES30.glEnableVertexAttribArray(aPositionLoc)
        GLES30.glVertexAttribPointer(aPositionLoc, 2, GLES30.GL_FLOAT, false, 4 * 4, quadBuffer)

        quadBuffer.position(2)
        GLES30.glEnableVertexAttribArray(aTexCoordLoc)
        GLES30.glVertexAttribPointer(aTexCoordLoc, 2, GLES30.GL_FLOAT, false, 4 * 4, quadBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)

        GLES30.glDisableVertexAttribArray(aPositionLoc)
        GLES30.glDisableVertexAttribArray(aTexCoordLoc)
    }

    fun release() {
        GLES30.glDeleteProgram(program)
    }
}
