package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.render.RenderResource
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.kgl.*

/**
 * Represents an OpenGL shading language (GLSL) shader program and provides methods for identifying and accessing shader
 * variables. Shader programs are configured by calling `setProgramSources` to specify the the GLSL vertex
 * shader and fragment shader source code, then made current by calling `useProgram`.
 */
abstract class AbstractShaderProgram: RenderResource {
    companion object {
        protected const val VERTEX_SHADER = 0
        protected const val FRAGMENT_SHADER = 1
    }

    protected abstract var programSources: Array<String>
    protected abstract val attribBindings: Array<String>
    protected lateinit var gl : Kgl
        private set
    /**
     * Indicates the approximate size of the OpenGL resources referenced by this GPU program.
     */
    val programLength get() = programSources.sumOf{str -> str.length}

    /**
     * Indicates the OpenGL program object associated with this GPU program.
     */
    protected var program = KglProgram.NONE
    protected var mustBuildProgram = true

    override fun release(dc: DrawContext) = deleteProgram(dc)

    fun useProgram(dc: DrawContext): Boolean {
        if (mustBuildProgram) {
            // Clear the program's build dirty bit.
            mustBuildProgram = false

            // Remove any existing GLSL program.
            if (program.isValid()) deleteProgram(dc)

            // Compile and link the GLSL program sources.
            buildProgram(dc)

            // Free memory, occupied by program sources
            programSources = emptyArray()

            // Give subclasses an opportunity to initialize default GLSL uniform values.
            if (program.isValid()) {
                val currentProgram = dc.currentProgram
                try {
                    dc.useProgram(program)
                    initProgram(dc)
                } finally {
                    dc.useProgram(currentProgram)
                }
            }
        }
        if (program.isValid()) dc.useProgram(program)
        return program.isValid()
    }

    protected open fun buildProgram(dc: DrawContext) {
        val vs = dc.gl.createShader(GL_VERTEX_SHADER)
        dc.gl.shaderSource(vs, programSources[VERTEX_SHADER])
        dc.gl.compileShader(vs)
        if (dc.gl.getShaderParameteri(vs, GL_COMPILE_STATUS) != GL_TRUE) {
            val msg = dc.gl.getShaderInfoLog(vs)
            dc.gl.deleteShader(vs)
            logMessage(
                ERROR, "ShaderProgram", "buildProgram", "Error compiling GL vertex shader \n$msg"
            )
            return
        }
        val fs = dc.gl.createShader(GL_FRAGMENT_SHADER)
        dc.gl.shaderSource(fs, programSources[FRAGMENT_SHADER])
        dc.gl.compileShader(fs)

        if (dc.gl.getShaderParameteri(vs, GL_COMPILE_STATUS) != GL_TRUE) {
            val msg = dc.gl.getShaderInfoLog(fs)
            dc.gl.deleteShader(vs)
            dc.gl.deleteShader(fs)
            logMessage(
                ERROR, "ShaderProgram", "buildProgram", "Error compiling GL fragment shader \n$msg"
            )
            return
        }
        val program = dc.gl.createProgram()
        dc.gl.attachShader(program, vs)
        dc.gl.attachShader(program, fs)
        for (i in attribBindings.indices) dc.gl.bindAttribLocation(program, i, attribBindings[i])
        dc.gl.linkProgram(program)
        dc.gl.deleteShader(vs)
        dc.gl.deleteShader(fs)
        if (dc.gl.getProgramParameteri(program, GL_LINK_STATUS) != GL_TRUE) {
            val msg = dc.gl.getProgramInfoLog(program)
            dc.gl.deleteProgram(program)
            logMessage(ERROR, "ShaderProgram", "buildProgram", "Error linking GL program \n$msg")
            return
        }
        this.program = program
    }

    protected open fun initProgram(dc: DrawContext) { gl = dc.gl }

    protected open fun deleteProgram(dc: DrawContext) {
        if (program.isValid()) {
            dc.gl.deleteProgram(program)
            program = KglProgram.NONE
        }
    }
}