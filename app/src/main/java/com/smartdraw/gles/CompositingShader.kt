package com.smartdraw.gles

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class CompositingShader {
    private val vertexShaderSource = """#version 300 es
        in vec2 a_position;
        in vec2 a_texCoord;
        uniform mat4 u_mvpMatrix;
        out vec2 v_texCoord;

        void main() {
            gl_Position = u_mvpMatrix * vec4(a_position, 0.0, 1.0);
            v_texCoord = a_texCoord;
        }
    """.trimIndent()

    private val fragmentShaderSource = """#version 300 es
        precision highp float;

        in vec2 v_texCoord;

        uniform sampler2D u_baseLayer;
        uniform sampler2D u_referenceLayer;
        uniform sampler2D u_activeStrokeLayer;

        uniform float u_referenceOpacity;
        uniform bool u_renderReference;
        uniform bool u_hasActiveStroke;
        uniform vec4 u_backgroundColor;

        out vec4 fragColor;

        vec4 blend(vec4 base, vec4 overlay, float opacity) {
            overlay.a *= opacity;
            return vec4(
                mix(base.rgb, overlay.rgb, overlay.a),
                max(base.a, overlay.a)
            );
        }

        void main() {
            vec4 bg = u_backgroundColor;
            vec4 baseImage = texture(u_baseLayer, v_texCoord);
            vec4 reference = texture(u_referenceLayer, v_texCoord);

            vec4 result = blend(bg, baseImage, 1.0);
            if (u_hasActiveStroke) {
                vec4 activeStroke = texture(u_activeStrokeLayer, v_texCoord);
                result = blend(result, activeStroke, 1.0);
            }
            if (u_renderReference) {
                result = blend(result, reference, u_referenceOpacity);
            }
            fragColor = result;
        }
    """.trimIndent()

    private val program: Int = GlesShaderUtil.createProgram(vertexShaderSource, fragmentShaderSource)
    private val aPositionLoc: Int = GLES30.glGetAttribLocation(program, "a_position")
    private val aTexCoordLoc: Int = GLES30.glGetAttribLocation(program, "a_texCoord")

    private val uBaseLayerLoc: Int = GLES30.glGetUniformLocation(program, "u_baseLayer")
    private val uReferenceLayerLoc: Int = GLES30.glGetUniformLocation(program, "u_referenceLayer")
    private val uActiveStrokeLayerLoc: Int = GLES30.glGetUniformLocation(program, "u_activeStrokeLayer")
    private val uReferenceOpacityLoc: Int = GLES30.glGetUniformLocation(program, "u_referenceOpacity")
    private val uRenderReferenceLoc: Int = GLES30.glGetUniformLocation(program, "u_renderReference")
    private val uHasActiveStrokeLoc: Int = GLES30.glGetUniformLocation(program, "u_hasActiveStroke")
    private val uBackgroundColorLoc: Int = GLES30.glGetUniformLocation(program, "u_backgroundColor")
    private val uMvpMatrixLoc: Int = GLES30.glGetUniformLocation(program, "u_mvpMatrix")

    private val quadBuffer: FloatBuffer

    companion object {
        private val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
    }

    init {
        // Quad with vertices in [0, 1] x [0, 1] for positioning via model matrix.
        // Y-Axis Inversion Fix: In OpenGL texture coordinates, (0,0) is bottom-left and (1,1) is top-left.
        // For our upright canvas quad (top-left at y=0, bottom-left at y=1):
        // top-left (x=0, y=0) -> (u=0, v=1.0)
        // top-right (x=1, y=0) -> (u=1, v=1.0)
        // bottom-left (x=0, y=1) -> (u=0, v=0.0)
        // bottom-right (x=1, y=1) -> (u=1, v=0.0)
        val quadVertices = floatArrayOf(
            0.0f, 0.0f,  0.0f, 1.0f,
            1.0f, 0.0f,  1.0f, 1.0f,
            0.0f, 1.0f,  0.0f, 0.0f,
            0.0f, 1.0f,  0.0f, 0.0f,
            1.0f, 0.0f,  1.0f, 1.0f,
            1.0f, 1.0f,  1.0f, 0.0f
        )
        quadBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadVertices)
        quadBuffer.position(0)
    }

    fun renderComposite(
        baseTextureId: Int,
        referenceTextureId: Int,
        referenceOpacity: Float,
        renderReference: Boolean,
        activeStrokeTextureId: Int = 0,
        backgroundColor: FloatArray = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f),
        mvpMatrix: FloatArray? = null
    ) {
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, baseTextureId)
        GLES30.glUniform1i(uBaseLayerLoc, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, referenceTextureId)
        GLES30.glUniform1i(uReferenceLayerLoc, 1)

        val hasActive = activeStrokeTextureId != 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (hasActive) activeStrokeTextureId else 0)
        GLES30.glUniform1i(uActiveStrokeLayerLoc, 2)
        GLES30.glUniform1i(uHasActiveStrokeLoc, if (hasActive) 1 else 0)

        GLES30.glUniform1f(uReferenceOpacityLoc, referenceOpacity)
        GLES30.glUniform1i(uRenderReferenceLoc, if (renderReference) 1 else 0)
        GLES30.glUniform4fv(uBackgroundColorLoc, 1, backgroundColor, 0)
        GLES30.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix ?: IDENTITY_MATRIX, 0)

        quadBuffer.position(0)
        GLES30.glEnableVertexAttribArray(aPositionLoc)
        GLES30.glVertexAttribPointer(aPositionLoc, 2, GLES30.GL_FLOAT, false, 4 * 4, quadBuffer)

        quadBuffer.position(2)
        GLES30.glEnableVertexAttribArray(aTexCoordLoc)
        GLES30.glVertexAttribPointer(aTexCoordLoc, 2, GLES30.GL_FLOAT, false, 4 * 4, quadBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)

        GLES30.glDisableVertexAttribArray(aPositionLoc)
        GLES30.glDisableVertexAttribArray(aTexCoordLoc)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun release() {
        GLES30.glDeleteProgram(program)
    }
}
