package com.smartdraw.gles

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class SmudgeShader {
    private val vertexShaderSource = """#version 300 es
        in vec2 a_position;
        in vec2 a_texCoord;

        uniform vec2 u_resolution;
        uniform vec2 u_brushCenter;
        uniform float u_brushRadius;

        out vec2 v_quadPos;
        out vec2 v_canvasTexCoord;

        void main() {
            vec2 worldPos = u_brushCenter + (a_position * u_brushRadius);
            vec2 clipSpace = (worldPos / u_resolution) * 2.0 - 1.0;
            clipSpace.y = -clipSpace.y;
            gl_Position = vec4(clipSpace, 0.0, 1.0);
            v_quadPos = a_position;
            // Texture V is inverted relative to canvas Y (OpenGL texture origin is bottom-left)
            v_canvasTexCoord = vec2(worldPos.x / u_resolution.x, 1.0 - (worldPos.y / u_resolution.y));
        }
    """.trimIndent()

    private val fragmentShaderSource = """#version 300 es
        precision highp float;

        in vec2 v_quadPos;
        in vec2 v_canvasTexCoord;

        uniform sampler2D u_sourceTexture;
        uniform vec2 u_resolution;
        uniform float u_brushRadius;
        uniform float u_blurStrength;
        uniform float u_brushOpacity;

        out vec4 fragColor;

        const vec2 offsets[9] = vec2[](
            vec2(-1.0, -1.0), vec2( 0.0, -1.0), vec2( 1.0, -1.0),
            vec2(-1.0,  0.0), vec2( 0.0,  0.0), vec2( 1.0,  0.0),
            vec2(-1.0,  1.0), vec2( 0.0,  1.0), vec2( 1.0,  1.0)
        );

        const float weights[9] = float[](
            0.0625, 0.125, 0.0625,
            0.125,  0.25,  0.125,
            0.0625, 0.125, 0.0625
        );

        void main() {
            float r_px = length(v_quadPos) * u_brushRadius;
            float alpha = 1.0 - smoothstep(u_brushRadius - 1.0, u_brushRadius + 1.0, r_px);
            if (alpha <= 0.001) {
                discard;
            }

            vec2 texelSize = 1.0 / u_resolution;
            vec4 blurredColor = vec4(0.0);
            for (int i = 0; i < 9; i++) {
                vec2 sampleCoord = v_canvasTexCoord + offsets[i] * texelSize * u_blurStrength;
                blurredColor += texture(u_sourceTexture, sampleCoord) * weights[i];
            }

            vec4 originalColor = texture(u_sourceTexture, v_canvasTexCoord);
            float mixFactor = alpha * clamp(u_brushOpacity, 0.0, 1.0);
            fragColor = mix(originalColor, blurredColor, mixFactor);
        }
    """.trimIndent()

    private val program: Int = GlesShaderUtil.createProgram(vertexShaderSource, fragmentShaderSource)
    private val aPositionLoc: Int = GLES30.glGetAttribLocation(program, "a_position")
    private val aTexCoordLoc: Int = GLES30.glGetAttribLocation(program, "a_texCoord")

    private val uResolutionLoc: Int = GLES30.glGetUniformLocation(program, "u_resolution")
    private val uBrushCenterLoc: Int = GLES30.glGetUniformLocation(program, "u_brushCenter")
    private val uBrushRadiusLoc: Int = GLES30.glGetUniformLocation(program, "u_brushRadius")
    private val uBlurStrengthLoc: Int = GLES30.glGetUniformLocation(program, "u_blurStrength")
    private val uBrushOpacityLoc: Int = GLES30.glGetUniformLocation(program, "u_brushOpacity")
    private val uSourceTextureLoc: Int = GLES30.glGetUniformLocation(program, "u_sourceTexture")

    private val quadBuffer: FloatBuffer

    init {
        val quadVertices = floatArrayOf(
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

    fun renderSmudgeStamp(
        canvasWidth: Float,
        canvasHeight: Float,
        centerX: Float,
        centerY: Float,
        radius: Float,
        blurStrength: Float,
        opacity: Float,
        sourceTextureId: Int
    ) {
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTextureId)
        GLES30.glUniform1i(uSourceTextureLoc, 0)

        GLES30.glUniform2f(uResolutionLoc, canvasWidth, canvasHeight)
        GLES30.glUniform2f(uBrushCenterLoc, centerX, centerY)
        GLES30.glUniform1f(uBrushRadiusLoc, radius)
        GLES30.glUniform1f(uBlurStrengthLoc, blurStrength)
        GLES30.glUniform1f(uBrushOpacityLoc, opacity)

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
