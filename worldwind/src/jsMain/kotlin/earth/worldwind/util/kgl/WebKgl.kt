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

    // Detect WebGL2 by constructor name rather than `instanceof`. `as?` compiles
    // to `gl instanceof WebGL2RenderingContext` where the class symbol resolves
    // to the main window's, but a popout-canvas context is an instance of the
    // popout window's class (separate Realm). The cross-Realm check returns
    // false for a valid WebGL2 context — [DrawContext] then asks for unsized
    // formats and WebGL2 rejects them. Constructor-name compare is Realm-agnostic
    // ([WorldWindow.createContext] already uses the same approach).
    private val gl2: WebGL2RenderingContext? =
        if (gl.asDynamic().constructor?.name == "WebGL2RenderingContext")
            gl.unsafeCast<WebGL2RenderingContext>() else null
    private val isWebGL2: Boolean get() = gl2 != null

    // Declared AFTER [gl2] so the initializers capture the post-construction value of
    // [isWebGL2] (Kotlin property initialization runs top-down).
    //
    // WebGL2: force GLSL ES 3.00 on every shader. dFdx/dFdy are core in ES 3.00 and legacy
    //   ES 1.00 shaders compile via the [translateLegacyGlsl] pass in [shaderSource] -
    //   identical strategy to [JoglKgl] (`#version 330 core` + the same legacy translation).
    //   Observed WebGL2 GLSL ES 1.00 compilers refuse the `OES_standard_derivatives` directive
    //   AND don't expose `dFdx` to legacy shaders either; ES 3.00 sidesteps both quirks.
    // WebGL1: leave the version empty (legacy ES 1.00) and emit the extension directive plus
    //   the [WW_HAS_DERIVATIVES] macro. WorldWindow also `getExtension`s OES_standard_derivatives
    //   for context-level enablement.
    override val glslVersion: String = if (isWebGL2) "#version 300 es\n" else ""
    override val glslDerivativesPrefix: String =
        if (isWebGL2) "#define WW_HAS_DERIVATIVES 1\n"
        else "#extension GL_OES_standard_derivatives : enable\n#define WW_HAS_DERIVATIVES 1\n"

    override fun getParameteri(pname: Int): Int = gl.getParameter(pname) as Int

    override fun getParameterf(pname: Int): Float = gl.getParameter(pname) as Float

    override fun getParameteriv(pname: Int): IntArray = gl.getParameter(pname) as IntArray

    override fun getParameterfv(pname: Int): FloatArray = gl.getParameter(pname) as FloatArray

    override fun createShader(type: Int) = KglShader(gl.createShader(type))

    override fun shaderSource(shader: KglShader, source: String) =
        gl.shaderSource(shader.obj, if (isWebGL2) translateLegacyGlsl(shader, source) else source)

    // Rewrite GLSL ES 1.00 syntax in-place so it compiles under `#version 300 es`. Same
    // mapping as [JoglKgl.translateLegacyGlsl] — keep them in sync. Idempotent: shaders
    // that already use modern syntax have no patterns to match and pass through unchanged
    // (e.g. [ViewshedKernelShaderProgram] which is native ES 3.00).
    //
    // Fragment shaders also get a `precision mediump float / int` default injected. ES 1.00
    // gives fragments an implicit default; ES 3.00 doesn't, and shaders that don't carry
    // their own `precision` block (e.g. [SightlineMomentsProgram], [DepthToColorProgram])
    // would otherwise fail with "No precision specified for (float)". Shaders that DO carry
    // their own block keep working - the later declaration overrides this default.
    private fun translateLegacyGlsl(shader: KglShader, source: String): String {
        val isVertex = gl.getShaderParameter(shader.obj, GL_SHADER_TYPE) == GL_VERTEX_SHADER
        var s = source
            .replace("attribute ", "in ")
            // Order matters: `texture2DProj(` must rewrite before `texture2D(` so the longer
            // legacy name routes to the modern projective overload, not to plain `texture(`.
            .replace("texture2DProj(", "textureProj(")
            .replace("textureCubeProj(", "textureProj(")
            .replace("texture2D(", "texture(")
            .replace("textureCube(", "texture(")
            // Strip `#extension` directives for features that were promoted to core in ES 3.00.
            // The directive on a core-promoted extension produces an "extension is not supported"
            // warning on WebGL2 even though the underlying feature works. Each entry is matched
            // as a whole line — partial matches inside comments / strings stay untouched.
            // (`\R` is Java-only; use explicit `\r?\n` so the regex also works on Kotlin/JS.)
            .replace(Regex("^\\s*#extension\\s+GL_OES_standard_derivatives.*(?:\\r?\\n)?", RegexOption.MULTILINE), "")
            .replace(Regex("^\\s*#extension\\s+GL_EXT_frag_depth.*(?:\\r?\\n)?", RegexOption.MULTILINE), "")
        s = if (isVertex) s.replace("varying ", "out ") else s.replace("varying ", "in ")
        if (!isVertex) {
            // Skip the version line and any leading #extension / blank lines — preamble must
            // sit after them (ES 3.00 requires extensions before any non-extension token).
            var insertAt = s.indexOf('\n') + 1
            while (insertAt < s.length) {
                val lineEnd = s.indexOf('\n', insertAt).let { if (it < 0) s.length else it }
                val line = s.substring(insertAt, lineEnd).trimStart()
                if (line.isEmpty() || line.startsWith("#extension")) {
                    insertAt = if (lineEnd < s.length) lineEnd + 1 else s.length
                } else break
            }
            val needsFragOut = "gl_FragColor" in s
            val preamble = "precision mediump float;\nprecision mediump int;\n" +
                if (needsFragOut) "out vec4 _wwFragColor;\n" else ""
            s = s.substring(0, insertAt) + preamble + s.substring(insertAt)
            if (needsFragOut) s = s.replace("gl_FragColor", "_wwFragColor")
        }
        return s
    }

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

    override fun bufferData(target: Int, size: Int, sourceData: ByteArray?, usage: Int, offset: Int) {
        val data = sourceData?.unsafeCast<Int8Array>()
            ?.let { if (offset == 0 && size == it.length) it else it.subarray(offset, offset + size) }
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

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: ByteArray) {
        gl.bufferSubData(target, offset, sourceData.unsafeCast<Int8Array>())
    }

    override fun mapAndCopyBufferRange(
        target: Int, offset: Int, length: Int, source: ByteArray, srcOffset: Int, access: Int
    ) {
        // WebGL2 has no buffer-mapping API (security model — pages can't get raw GPU
        // pointers). Fall back to bufferSubData; the [access] hint is ignored.
        val view = source.unsafeCast<Int8Array>()
            .let { if (srcOffset == 0 && length == it.length) it else it.subarray(srcOffset, srcOffset + length) }
        gl.bufferSubData(target, offset, view)
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

    override fun texSubImage2D(
        target: Int, level: Int, xoffset: Int, yoffset: Int, width: Int, height: Int, format: Int, type: Int, buffer: ByteArray?
    ) = gl.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, buffer?.unsafeCast<Int8Array>())

    override fun texSubImage2D(
        target: Int, level: Int, xoffset: Int, yoffset: Int, width: Int, height: Int, format: Int, type: Int, offset: Int
    ) {
        // PBO-bound texSubImage2D — only available on WebGL2. The gl-binding's typed
        // signature for the int-offset variant isn't on the legacy WebGL1
        // RenderingContext binding, so route through asDynamic to call the real DOM
        // overload. Caller is responsible for ensuring a WebGL2 context.
        requireGl2().asDynamic().texSubImage2D(
            target, level, xoffset, yoffset, width, height, format, type, offset
        )
    }

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
