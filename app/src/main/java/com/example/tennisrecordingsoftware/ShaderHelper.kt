package com.example.tennisrecordingsoftware

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object ShaderHelper {

    fun createFloatBuffer(coords: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(coords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(coords)
                position(0)
            }
    }

    fun loadProgram(vSource: String, fSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fSource)
        if (vertexShader == 0 || fragmentShader == 0) return 0

        val program = GLES20.glCreateProgram()
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e("ShaderHelper", "Could not link program: " + GLES20.glGetProgramInfoLog(program))
                GLES20.glDeleteProgram(program)
                return 0
            }
        }
        return program
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        if (shader != 0) {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e("ShaderHelper", "Could not compile shader $shaderType: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                shader = 0
            }
        }
        return shader
    }
}
