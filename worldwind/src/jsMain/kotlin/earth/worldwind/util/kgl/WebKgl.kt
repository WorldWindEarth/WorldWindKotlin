package earth.worldwind.util.kgl

import org.khronos.webgl.*

actual data class KglShader(val obj: WebGLShader? = null) {
    actual companion object{ actual val NONE = KglShader() }
    actual fun isValid() = obj != null
}

actual data class KglProgram(val obj: WebGLProgram? = null) {
    actual companion object{ actual val NONE = KglProgram() }
    actual fun isValid() = obj != null
}

actual data class KglUniformLocation(val obj: WebGLUniformLocation? = null) {
    actual companion object{ actual val NONE = KglUniformLocation() }
    actual fun isValid() = obj != null
}

actual data class KglBuffer(val obj: WebGLBuffer? = null) {
    actual companion object{ actual val NONE = KglBuffer() }
    actual fun isValid() = obj != null
}

actual data class KglTexture(val obj: WebGLTexture? = null) {
    actual companion object{ actual val NONE = KglTexture() }
    actual fun isValid() = obj != null
}

actual data class KglFramebuffer(val obj: WebGLFramebuffer? = null) {
    actual companion object{ actual val NONE = KglFramebuffer() }
    actual fun isValid() = obj != null
}

class WebKgl(val gl: WebGLRenderingContext) : Kgl {

    override val hasMaliOOMBug = false

    override fun getParameteri(pname: Int): Int = gl.getParameter(pname) as Int

    override fun getParameterf(pname: Int): Float = gl.getParameter(pname) as Float

    override fun getParameteriv(pname: Int): IntArray = gl.getParameter(pname) as IntArray

    override fun getParameterfv(pname: Int): FloatArray = gl.getParameter(pname) as FloatArray

    override fun createShader(type: Int) = KglShader(gl.createShader(type))

    override fun shaderSource(shader: KglShader, source: String) = gl.shaderSource(shader.obj, source)

    override fun compileShader(shader: KglShader) = gl.compileShader(shader.obj)

    override fun deleteShader(shader: KglShader) = gl.deleteShader(shader.obj)

    override fun getShaderParameteri(shader: KglShader, pname: Int): Int {
        val value = gl.getShaderParameter(shader.obj, pname)
        return if (value is Boolean) { if (value) GL_TRUE else GL_FALSE } else value as Int
    }

    override fun getProgramInfoLog(program: KglProgram): String = gl.getProgramInfoLog(program.obj) ?: ""

    override fun getShaderInfoLog(shader: KglShader): String = gl.getShaderInfoLog(shader.obj) ?: ""

    override fun createProgram() = KglProgram(gl.createProgram())

    override fun deleteProgram(program: KglProgram) = gl.deleteProgram(program.obj)

    override fun attachShader(program: KglProgram, shader: KglShader) = gl.attachShader(program.obj, shader.obj)

    override fun linkProgram(program: KglProgram) = gl.linkProgram(program.obj)

    override fun useProgram(program: KglProgram) = gl.useProgram(program.obj)

    override fun getProgramParameteri(program: KglProgram, pname: Int): Int {
        val value = gl.getProgramParameter(program.obj, pname)
        return if (value is Boolean) { if (value) GL_TRUE else GL_FALSE } else value as Int
    }

    override fun getUniformLocation(program: KglProgram, name: String) =
        KglUniformLocation(gl.getUniformLocation(program.obj, name))

    override fun bindAttribLocation(program: KglProgram, index: Int, name: String) =
        gl.bindAttribLocation(program.obj, index, name)

    override fun createBuffer() = KglBuffer(gl.createBuffer())

    override fun bindBuffer(target: Int, buffer: KglBuffer) = gl.bindBuffer(target, buffer.obj)

    override fun bufferData(target: Int, size: Int, sourceData: ShortArray?, usage: Int, offset: Int) {
        val arr = sourceData?.let {sourceData.unsafeCast<Int16Array>()}
        val len = size / 2
        gl.bufferData(target, arr?.let { if (offset == 0 && len == arr.length) arr else arr.subarray(offset, offset + len)}, usage)
    }

    override fun bufferData(target: Int, size: Int, sourceData: IntArray?, usage: Int, offset: Int) {
        val arr = sourceData?.let { sourceData.unsafeCast<Int32Array>()}
        val len = size / 4
        gl.bufferData(target, arr?.let { if (offset == 0 && len == arr.length) arr else arr.subarray(offset, offset + len)}, usage)
    }

    override fun bufferData(target: Int, size: Int, sourceData: FloatArray?, usage: Int, offset: Int) {
        val arr = sourceData?.let {sourceData.unsafeCast<Float32Array>()}
        val len = size / 4
        gl.bufferData(target, arr?.let { if (offset == 0 && len == arr.length) arr else arr.subarray(offset, offset + len)}, usage)
    }

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: ShortArray){
        val arr = sourceData.unsafeCast<Int16Array>()
        gl.bufferSubData(target, offset, arr)
    }

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: IntArray){
        val arr = sourceData.unsafeCast<Int32Array>()
        gl.bufferSubData(target, offset, arr)
    }

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: FloatArray){
        val arr = sourceData.unsafeCast<Float32Array>()
        gl.bufferSubData(target, offset, arr)
    }

    override fun deleteBuffer(buffer: KglBuffer) = gl.deleteBuffer(buffer.obj)

    override fun vertexAttribPointer(
        location: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int
    ) = gl.vertexAttribPointer(location, size, type, normalized, stride, offset)

    override fun enableVertexAttribArray(location: Int) = gl.enableVertexAttribArray(location)

    override fun disableVertexAttribArray(location: Int) = gl.disableVertexAttribArray(location)

    override fun enable(cap: Int) = gl.enable(cap)

    override fun disable(cap: Int) = gl.disable(cap)

    override fun uniform1f(location: KglUniformLocation, f: Float) =
        gl.uniform1f(location.obj, f)

    override fun uniform1fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) {
        val arr = value.unsafeCast<Float32Array>()
        val len = count * 1
        gl.uniform1fv(location.obj, if (offset == 0 && len == arr.length) arr else arr.subarray(offset, offset + len))
    }

    override fun uniform1i(location: KglUniformLocation, i: Int) =
        gl.uniform1i(location.obj, i)

    override fun uniform2f(location: KglUniformLocation, x: Float, y: Float) =
        gl.uniform2f(location.obj, x, y)

    override fun uniform2fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) {
        val arr = value.unsafeCast<Float32Array>()
        val len = count * 2
        gl.uniform2fv(location.obj, if (offset == 0 && len == arr.length) arr else arr.subarray(offset, offset + len))
    }

    override fun uniform2i(location: KglUniformLocation, x: Int, y: Int) =
        gl.uniform2i(location.obj, x, y)

    override fun uniform3f(location: KglUniformLocation, x: Float, y: Float, z: Float) =
        gl.uniform3f(location.obj, x, y, z)

    override fun uniform3fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) {
        val arr = value.unsafeCast<Float32Array>()
        val len = count * 3
        gl.uniform3fv(location.obj, if (offset == 0 && len == arr.length) arr else arr.subarray(offset, offset + len))
    }

    override fun uniform3i(location: KglUniformLocation, x: Int, y: Int, z: Int) =
        gl.uniform3i(location.obj, x, y, z)

    override fun uniform4f(location: KglUniformLocation, x: Float, y: Float, z: Float, w: Float) =
        gl.uniform4f(location.obj, x, y, z, w)

    override fun uniform4fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) {
        val arr = value.unsafeCast<Float32Array>()
        val len = count * 4
        gl.uniform4fv(location.obj, if (offset == 0 && len == arr.length) arr else arr.subarray(offset, offset + len))
    }

    override fun uniform4i(location: KglUniformLocation, x: Int, y: Int, z: Int, w: Int) = gl.uniform4i(location.obj, x, y, z, w)

    override fun uniformMatrix3fv(location: KglUniformLocation, count: Int, transpose: Boolean, value: FloatArray, offset: Int) {
        val arr = value.unsafeCast<Float32Array>()
        val len = count * 9
        gl.uniformMatrix3fv(location.obj, transpose, if (offset == 0 && len == arr.length) arr else arr.subarray(offset, offset + len))
    }

    override fun uniformMatrix4fv(location: KglUniformLocation, count: Int, transpose: Boolean, value: FloatArray, offset: Int) {
        val arr = value.unsafeCast<Float32Array>()
        val len = count * 16
        gl.uniformMatrix4fv(location.obj, transpose, if (offset == 0 && len == arr.length) arr else arr.subarray(offset, offset + len))
    }

    override fun lineWidth(width: Float) = gl.lineWidth(width)

    override fun polygonOffset(factor: Float, units: Float) = gl.polygonOffset(factor, units)

    override fun cullFace(mode: Int) = gl.cullFace(mode)

    override fun frontFace(mode: Int) = gl.frontFace(mode)

    override fun depthFunc(func: Int) = gl.depthFunc(func)

    override fun depthMask(mask: Boolean) = gl.depthMask(mask)

    override fun blendFunc(sFactor: Int, dFactor: Int) = gl.blendFunc(sFactor, dFactor)

    override fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean) = gl.colorMask(r, g, b, a)

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = gl.viewport(x, y, width, height)

    override fun clear(mask: Int) = gl.clear(mask)

    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = gl.clearColor(r, g, b, a)

    override fun createTexture() = KglTexture(gl.createTexture())

    override fun deleteTexture(texture: KglTexture) = gl.deleteTexture(texture.obj)

    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, buffer: ByteArray?
    ) = gl.texImage2D(target, level, internalFormat, width, height, border, format, type, buffer?.unsafeCast<Int8Array>())

    override fun activeTexture(texture: Int) = gl.activeTexture(texture)

    override fun bindTexture(target: Int, texture: KglTexture) = gl.bindTexture(target, texture.obj)

    override fun generateMipmap(target: Int) = gl.generateMipmap(target)

    override fun texParameteri(target: Int, pname: Int, value: Int) = gl.texParameteri(target, pname, value)

    override fun drawArrays(mode: Int, first: Int, count: Int) = gl.drawArrays(mode, first, count)

    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) = gl.drawElements(mode, count, type, offset)

    override fun getError() = gl.getError()

    override fun finish() = gl.finish()

    override fun bindFramebuffer(target: Int, framebuffer: KglFramebuffer) = gl.bindFramebuffer(target, framebuffer.obj)

    override fun createFramebuffer() = KglFramebuffer(gl.createFramebuffer())

    override fun deleteFramebuffer(framebuffer: KglFramebuffer) = gl.deleteFramebuffer(framebuffer.obj)

    override fun checkFramebufferStatus(target: Int) = gl.checkFramebufferStatus(target)

    override fun framebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: KglTexture, level: Int) =
        gl.framebufferTexture2D(target, attachment, textarget, texture.obj, level)

    override fun readPixels(
        x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, buffer: ByteArray
    ) = gl.readPixels(x, y, width, height, format, type, Uint8Array(buffer.unsafeCast<Int8Array>().buffer))

    override fun pixelStorei(pname: Int, param: Int) = gl.pixelStorei(pname, param)
}
