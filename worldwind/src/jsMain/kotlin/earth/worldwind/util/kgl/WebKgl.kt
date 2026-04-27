package earth.worldwind.util.kgl

import org.khronos.webgl.*
import web.gl.WebGL2RenderingContext
import web.gl.WebGLRenderbuffer

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

actual data class KglRenderbuffer(val obj: WebGLRenderbuffer? = null) {
    actual companion object{ actual val NONE = KglRenderbuffer() }
    actual fun isValid() = obj != null
}

actual data class KglSync(val obj: web.gl.WebGLSync? = null) {
    actual companion object { actual val NONE = KglSync() }
    actual fun isValid() = obj != null
}

class WebKgl(val gl: WebGLRenderingContext) : Kgl {

    override val hasMaliOOMBug = false
    override val glslVersion3 = "#version 300 es\n"

    // `as?` compiles to an `instanceof WebGL2RenderingContext` runtime check + cast. The
    // cast through `Any` is needed because the static type of [gl] is the legacy
    // `org.khronos.webgl.WebGLRenderingContext`, which Kotlin treats as unrelated to the
    // kotlin-wrappers `web.gl.WebGL2RenderingContext`. Resolves to non-null whenever
    // WorldWindow.createContext got a WebGL2 context back.
    private val gl2: WebGL2RenderingContext? = (gl as Any) as? WebGL2RenderingContext
    private val isWebGL2: Boolean get() = gl2 != null

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
        val len = size / 2
        val data = sourceData?.unsafeCast<Int16Array>()
            ?.let { if (offset == 0 && len == it.length) it else it.subarray(offset, offset + len) }
        if (data != null) gl.bufferData(target, data, usage) else gl.bufferData(target, size, usage)
    }

    override fun bufferData(target: Int, size: Int, sourceData: IntArray?, usage: Int, offset: Int) {
        val len = size / 4
        val data = sourceData?.unsafeCast<Int32Array>()
            ?.let { if (offset == 0 && len == it.length) it else it.subarray(offset, offset + len) }
        if (data != null) gl.bufferData(target, data, usage) else gl.bufferData(target, size, usage)
    }

    override fun bufferData(target: Int, size: Int, sourceData: FloatArray?, usage: Int, offset: Int) {
        val len = size / 4
        val data = sourceData?.unsafeCast<Float32Array>()
            ?.let { if (offset == 0 && len == it.length) it else it.subarray(offset, offset + len) }
        if (data != null) gl.bufferData(target, data, usage) else gl.bufferData(target, size, usage)
    }

    override fun bufferData(target: Int, size: Int, usage: Int) = gl.bufferData(target, size, usage)

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: ShortArray) {
        gl.bufferSubData(target, offset, sourceData.unsafeCast<Int16Array>())
    }

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: IntArray) {
        gl.bufferSubData(target, offset, sourceData.unsafeCast<Int32Array>())
    }

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: FloatArray) {
        gl.bufferSubData(target, offset, sourceData.unsafeCast<Float32Array>())
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

    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, buffer: FloatArray?
    ) = gl.texImage2D(target, level, internalFormat, width, height, border, format, type, buffer?.unsafeCast<Float32Array>())

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

    // MSAA framebuffers and sized internal formats are both WebGL2-core / WebGL1-absent.
    override val supportsMultisampleFBO get() = isWebGL2
    override val supportsSizedTextureFormats get() = isWebGL2

    // Renderbuffer + multisample ops below route through `gl2`; on WebGL1 `requireGl2()`
    // throws. Call sites must guard with `supportsMultisampleFBO` first. Int args are
    // `unsafeCast` to `web.gl.GLenum` / `GLbitfield` (sealed external interfaces wrapping
    // Int at runtime — the JS layer treats them as numbers regardless).
    override fun createRenderbuffer(): KglRenderbuffer =
        KglRenderbuffer(requireGl2().createRenderbuffer())
    override fun deleteRenderbuffer(renderbuffer: KglRenderbuffer) =
        requireGl2().deleteRenderbuffer(renderbuffer.obj)
    override fun bindRenderbuffer(target: Int, renderbuffer: KglRenderbuffer) =
        requireGl2().bindRenderbuffer(target.glEnum(), renderbuffer.obj)
    override fun renderbufferStorageMultisample(target: Int, samples: Int, internalFormat: Int, width: Int, height: Int) =
        requireGl2().renderbufferStorageMultisample(target.glEnum(), samples, internalFormat.glEnum(), width, height)
    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: KglRenderbuffer) =
        requireGl2().framebufferRenderbuffer(target.glEnum(), attachment.glEnum(), renderbufferTarget.glEnum(), renderbuffer.obj)
    override fun blitFramebuffer(
        srcX0: Int, srcY0: Int, srcX1: Int, srcY1: Int,
        dstX0: Int, dstY0: Int, dstX1: Int, dstY1: Int,
        mask: Int, filter: Int
    ) = requireGl2().blitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask.glBitfield(), filter.glEnum())
    private fun requireGl2(): WebGL2RenderingContext =
        gl2 ?: throw UnsupportedOperationException("WebGL2 required for MSAA / multisample renderbuffer operations")
    private fun Int.glEnum(): web.gl.GLenum = unsafeCast<web.gl.GLenum>()
    private fun Int.glBitfield(): web.gl.GLbitfield = unsafeCast<web.gl.GLbitfield>()

    override fun readPixels(
        x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, buffer: ByteArray
    ) = gl.readPixels(x, y, width, height, format, type, Uint8Array(buffer.unsafeCast<Int8Array>().buffer))

    // PBO-target readPixels and the sync API are WebGL2-only. Routed through gl2 (the
    // kotlin-wrappers WebGL2 surface) where the necessary overloads live.
    override fun readPixelsToBuffer(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, offset: Int) =
        requireGl2().readPixels(x, y, width, height, format.glEnum(), type.glEnum(), offset)

    override fun getBufferSubData(target: Int, srcOffset: Int, dst: ByteArray) {
        // The kotlin-wrappers `web.gl.WebGL2RenderingContext.getBufferSubData` takes its own
        // `ArrayBufferView<ArrayBufferLike>` generic, but the runtime argument is the same JS
        // typed-array — the legacy `org.khronos.webgl.Uint8Array` we already use elsewhere in
        // this file. Routing through `asDynamic()` bypasses the static type-graph mismatch
        // without forcing a deep dependency on `web.buffer.*`.
        val view = Uint8Array(dst.unsafeCast<Int8Array>().buffer)
        requireGl2().asDynamic().getBufferSubData(target.glEnum(), srcOffset, view)
    }

    // Sync API routes through asDynamic() to sidestep the kotlin-wrappers type maze
    // (`GLenum`/`GLbitfield`/`GLuint64`/`GLsync` are all opaque generics that don't compose
    // smoothly with our Int-based Kgl interface). The runtime calls are the same; the
    // dynamic dispatch just lets us pass plain numbers and receive plain numbers back.
    override fun fenceSync(): KglSync {
        val obj = requireGl2().asDynamic().fenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            .unsafeCast<web.gl.WebGLSync?>()
        return KglSync(obj)
    }

    override fun isSyncSignalled(sync: KglSync): Boolean {
        val obj = sync.obj ?: return false
        // 0 timeout + no flush: returns immediately with status. ALREADY_SIGNALED or
        // CONDITION_SATISFIED both indicate the fence is reached; everything else is "not yet".
        val result = requireGl2().asDynamic().clientWaitSync(obj, 0, 0).unsafeCast<Int>()
        return result == GL_ALREADY_SIGNALED || result == GL_CONDITION_SATISFIED
    }

    override fun deleteSync(sync: KglSync) {
        sync.obj?.let { requireGl2().asDynamic().deleteSync(it) }
    }

    override fun pixelStorei(pname: Int, param: Int) = gl.pixelStorei(pname, param)
}
