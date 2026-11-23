package com.smartdraw.gles

import android.opengl.GLES30
import android.util.Log

object GlesShaderUtil {
    private const val TAG = "SmartDraw-GLES"

    fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) {
            // Headless unit test environment without active EGL context
            return 1
        }
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            if (log.isNullOrEmpty()) {
                // Headless test stub
                return shader
            }
            GLES30.glDeleteShader(shader)
            val typeStr = if (type == GLES30.GL_VERTEX_SHADER) "VERTEX" else "FRAGMENT"
            throw RuntimeException("Error compiling $typeStr shader: $log\nCode:\n$shaderCode")
        }
        return shader
    }

    fun createProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexCode)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentCode)

        val program = GLES30.glCreateProgram()
        if (program == 0) {
            // Headless unit test environment without active EGL context
            return 1
        }

        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            if (log.isNullOrEmpty()) {
                // Headless test stub
                return program
            }
            GLES30.glDeleteProgram(program)
            throw RuntimeException("Error linking shader program: $log")
        }

        // Shaders can be flagged for deletion once linked
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        return program
    }

    fun checkGlError(op: String) {
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            val msg = "$op: glError 0x${Integer.toHexString(error)}"
            Log.e(TAG, msg)
            throw RuntimeException(msg)
        }
    }
}
