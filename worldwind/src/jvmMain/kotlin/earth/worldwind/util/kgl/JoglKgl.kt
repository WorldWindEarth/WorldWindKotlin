package earth.worldwind.util.kgl

import com.jogamp.opengl.GL3ES3
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer

class JoglKgl(private val gl: GL3ES3) : Kgl {
    private val arrI = IntArray(16)
    private val arrF = FloatArray(16)

    override val hasMaliOOMBug = false

    override fun getParameteri(pname: Int): Int {
        gl.glGetIntegerv(pname, arrI, 0)
        return arrI[0]
    }

    override fun getParameterf(pname: Int): Float {
        gl.glGetFloatv(pname, arrF, 0)
        return arrF[0]
    }

    override fun getParameteriv(pname: Int): IntArray {
        gl.glGetIntegerv(pname, arrI, 0)
        return arrI
    }

    override fun getParameterfv(pname: Int): FloatArray {
        gl.glGetFloatv(pname, arrF, 0)
        return arrF
    }

    override fun createShader(type: Int) = KglShader(gl.glCreateShader(type))

    override fun shaderSource(shader: KglShader, source: String) {
        arrI[0] = source.length
        gl.glShaderSource(shader.id, 1, arrayOf(source), IntBuffer.wrap(arrI))
    }

    override fun compileShader(shader: KglShader) = gl.glCompileShader(shader.id)

    override fun deleteShader(shader: KglShader) = gl.glDeleteShader(shader.id)

    override fun getShaderParameteri(shader: KglShader, pname: Int): Int {
        gl.glGetShaderiv(shader.id, pname, arrI, 0)
        return arrI[0]
    }

    override fun getProgramInfoLog(program: KglProgram): String {
        val bufSize = 1024
        val buffer = ByteArray(bufSize)
        gl.glGetProgramInfoLog(program.id, bufSize, arrI, 0, buffer, 0)
        return buffer.decodeToString(0, arrI[0])
    }

    override fun getShaderInfoLog(shader: KglShader): String {
        val bufSize = 1024
        val buffer = ByteArray(bufSize)
        gl.glGetShaderInfoLog(shader.id, bufSize, arrI, 0, buffer, 0)
        return buffer.decodeToString(0, arrI[0])
    }

    override fun createProgram() = KglProgram(gl.glCreateProgram())

    override fun deleteProgram(program: KglProgram) = gl.glDeleteShader(program.id)

    override fun attachShader(program: KglProgram, shader: KglShader) = gl.glAttachShader(program.id, shader.id)

    override fun linkProgram(program: KglProgram) = gl.glLinkProgram(program.id)

    override fun useProgram(program: KglProgram) = gl.glUseProgram(program.id)

    override fun getProgramParameteri(program: KglProgram, pname: Int): Int {
        gl.glGetProgramiv(program.id, pname, arrI, 0)
        return arrI[0]
    }

    override fun getUniformLocation(program: KglProgram, name: String) =
        KglUniformLocation(gl.glGetUniformLocation(program.id, name))

    override fun bindAttribLocation(program: KglProgram, index: Int, name: String) =
        gl.glBindAttribLocation(program.id, index, name)

    override fun createBuffer(): KglBuffer {
        gl.glGenBuffers(1, arrI, 0)
        return KglBuffer(arrI[0])
    }

    override fun bindBuffer(target: Int, buffer: KglBuffer) = gl.glBindBuffer(target, buffer.id)

    override fun bufferData(target: Int, size: Int, sourceData: ShortArray, usage: Int, offset: Int) =
        gl.glBufferData(target, size.toLong(), ShortBuffer.wrap(sourceData, offset, sourceData.size - offset), usage)

    override fun bufferData(target: Int, size: Int, sourceData: FloatArray, usage: Int, offset: Int) =
        gl.glBufferData(target, size.toLong(), FloatBuffer.wrap(sourceData, offset, sourceData.size - offset), usage)

    override fun deleteBuffer(buffer: KglBuffer) {
        arrI[0] = buffer.id
        gl.glDeleteBuffers(1, arrI, 0)
    }

    override fun vertexAttribPointer(
        location: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int
    ) = gl.glVertexAttribPointer(location, size, type, normalized, stride, offset.toLong())

    override fun enableVertexAttribArray(location: Int) = gl.glEnableVertexAttribArray(location)

    override fun disableVertexAttribArray(location: Int) = gl.glDisableVertexAttribArray(location)

    override fun enable(cap: Int) = gl.glEnable(cap)

    override fun disable(cap: Int) = gl.glDisable(cap)

    override fun uniform1f(location: KglUniformLocation, f: Float) = gl.glUniform1f(location.id, f)

    override fun uniform1fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) = gl.glUniform1fv(location.id, count, value, offset)

    override fun uniform1i(location: KglUniformLocation, i: Int) = gl.glUniform1i(location.id, i)

    override fun uniform2f(location: KglUniformLocation, x: Float, y: Float) = gl.glUniform2f(location.id, x, y)

    override fun uniform2fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) = gl.glUniform2fv(location.id, count, value, offset)

    override fun uniform2i(location: KglUniformLocation, x: Int, y: Int) = gl.glUniform2i(location.id, x, y)

    override fun uniform3f(location: KglUniformLocation, x: Float, y: Float, z: Float) = gl.glUniform3f(location.id, x, y, z)

    override fun uniform3fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) = gl.glUniform3fv(location.id, count, value, offset)

    override fun uniform3i(location: KglUniformLocation, x: Int, y: Int, z: Int) = gl.glUniform3i(location.id, x, y, z)

    override fun uniform4f(location: KglUniformLocation, x: Float, y: Float, z: Float, w: Float) = gl.glUniform4f(location.id, x, y, z, w)

    override fun uniform4fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) = gl.glUniform4fv(location.id, count, value, offset)

    override fun uniform4i(location: KglUniformLocation, x: Int, y: Int, z: Int, w: Int) = gl.glUniform4i(location.id, x, y, z, w)

    override fun uniformMatrix3fv(location: KglUniformLocation, count: Int, transpose: Boolean, value: FloatArray, offset: Int) =
        gl.glUniformMatrix3fv(location.id, count, transpose, value, offset)

    override fun uniformMatrix4fv(location: KglUniformLocation, count: Int, transpose: Boolean, value: FloatArray, offset: Int) =
        gl.glUniformMatrix4fv(location.id, count, transpose, value, offset)

    override fun lineWidth(width: Float) = gl.glLineWidth(width)

    override fun polygonOffset(factor: Float, units: Float) = gl.glPolygonOffset(factor, units)

    override fun cullFace(mode: Int) = gl.glCullFace(mode)

    override fun frontFace(mode: Int) = gl.glFrontFace(mode)

    override fun depthFunc(func: Int) = gl.glDepthFunc(func)

    override fun depthMask(mask: Boolean) = gl.glDepthMask(mask)

    override fun blendFunc(sFactor: Int, dFactor: Int) = gl.glBlendFunc(sFactor, dFactor)

    override fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean) = gl.glColorMask(r, g, b, a)

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = gl.glViewport(x, y, width, height)

    override fun clear(mask: Int) = gl.glClear(mask)

    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = gl.glClearColor(r, g, b, a)

    override fun createTexture(): KglTexture {
        gl.glGenTextures(1, arrI, 0)
        return KglTexture(arrI[0])
    }

    override fun deleteTexture(texture: KglTexture) {
        arrI[0] = texture.id
        gl.glDeleteTextures(1, arrI, 0)
    }

    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, buffer: ByteArray?
    ) = gl.glTexImage2D(target, level, internalFormat, width, height, border, format, type, buffer?.let{ByteBuffer.wrap(it)})

    override fun activeTexture(texture: Int) = gl.glActiveTexture(texture)

    override fun bindTexture(target: Int, texture: KglTexture) = gl.glBindTexture(target, texture.id)

    override fun generateMipmap(target: Int) = gl.glGenerateMipmap(target)

    override fun texParameteri(target: Int, pname: Int, value: Int) = gl.glTexParameteri(target, pname, value)

    override fun drawArrays(mode: Int, first: Int, count: Int) = gl.glDrawArrays(mode, first, count)

    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) = gl.glDrawElements(mode, count, type, offset.toLong())

    override fun getError() = gl.glGetError()

    override fun finish() = gl.glFinish()

    override fun bindFramebuffer(target: Int, framebuffer: KglFramebuffer) = gl.glBindFramebuffer(target, framebuffer.id)

    override fun createFramebuffer(): KglFramebuffer {
        gl.glGenFramebuffers(1, arrI, 0)
        return KglFramebuffer(arrI[0])
    }

    override fun deleteFramebuffer(framebuffer: KglFramebuffer) {
        arrI[0] = framebuffer.id
        gl.glDeleteFramebuffers(1, arrI, 0)
    }

    override fun checkFramebufferStatus(target: Int) = gl.glCheckFramebufferStatus(target)

    override fun framebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: KglTexture, level: Int) =
        gl.glFramebufferTexture2D(target, attachment, textarget, texture.id, level)

    override fun readPixels(
        x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, buffer: ByteArray
    ) = gl.glReadPixels(x, y, width, height, format, type, ByteBuffer.wrap(buffer))

    override fun pixelStorei(pname: Int, param: Int) = gl.glPixelStorei(pname, param)
}

