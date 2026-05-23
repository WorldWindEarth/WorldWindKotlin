@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package earth.worldwind.util.kgl

import cnames.structs.__GLsync
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.memcpy
import platform.gles3.GL_ALREADY_SIGNALED
import platform.gles3.GL_CONDITION_SATISFIED
import platform.gles3.GL_SYNC_GPU_COMMANDS_COMPLETE
import platform.gles3.glActiveTexture
import platform.gles3.glAttachShader
import platform.gles3.glBindAttribLocation
import platform.gles3.glBindBuffer
import platform.gles3.glBindFramebuffer
import platform.gles3.glBindRenderbuffer
import platform.gles3.glBindTexture
import platform.gles3.glBlendFunc
import platform.gles3.glBlitFramebuffer
import platform.gles3.glBufferData
import platform.gles3.glBufferSubData
import platform.gles3.glCheckFramebufferStatus
import platform.gles3.glClear
import platform.gles3.glClearColor
import platform.gles3.glClientWaitSync
import platform.gles3.glColorMask
import platform.gles3.glCompileShader
import platform.gles3.glCreateProgram
import platform.gles3.glCreateShader
import platform.gles3.glCullFace
import platform.gles3.glDeleteBuffers
import platform.gles3.glDeleteFramebuffers
import platform.gles3.glDeleteProgram
import platform.gles3.glDeleteRenderbuffers
import platform.gles3.glDeleteShader
import platform.gles3.glDeleteSync
import platform.gles3.glDeleteTextures
import platform.gles3.glDepthFunc
import platform.gles3.glDepthMask
import platform.gles3.glDisable
import platform.gles3.glDisableVertexAttribArray
import platform.gles3.glDrawArrays
import platform.gles3.glDrawElements
import platform.gles3.glEnable
import platform.gles3.glEnableVertexAttribArray
import platform.gles3.glFenceSync
import platform.gles3.glFinish
import platform.gles3.glFramebufferRenderbuffer
import platform.gles3.glFramebufferTexture2D
import platform.gles3.glFrontFace
import platform.gles3.glGenBuffers
import platform.gles3.glGenFramebuffers
import platform.gles3.glGenRenderbuffers
import platform.gles3.glGenTextures
import platform.gles3.glGenerateMipmap
import platform.gles3.glGetError
import platform.gles3.glGetFloatv
import platform.gles3.glGetIntegerv
import platform.gles3.glGetProgramInfoLog
import platform.gles3.glGetProgramiv
import platform.gles3.glGetShaderInfoLog
import platform.gles3.glGetShaderiv
import platform.gles3.glGetString
import platform.gles3.glGetUniformLocation
import platform.gles3.glLineWidth
import platform.gles3.glLinkProgram
import platform.gles3.glMapBufferRange
import platform.gles3.glPixelStorei
import platform.gles3.glPolygonOffset
import platform.gles3.glReadPixels
import platform.gles3.glRenderbufferStorageMultisample
import platform.gles3.glShaderSource
import platform.gles3.glTexImage2D
import platform.gles3.glTexParameteri
import platform.gles3.glTexSubImage2D
import platform.gles3.glUniform1f
import platform.gles3.glUniform1fv
import platform.gles3.glUniform1i
import platform.gles3.glUniform2f
import platform.gles3.glUniform2fv
import platform.gles3.glUniform2i
import platform.gles3.glUniform3f
import platform.gles3.glUniform3fv
import platform.gles3.glUniform3i
import platform.gles3.glUniform4f
import platform.gles3.glUniform4fv
import platform.gles3.glUniform4i
import platform.gles3.glUniformMatrix3fv
import platform.gles3.glUniformMatrix4fv
import platform.gles3.glUnmapBuffer
import platform.gles3.glUseProgram
import platform.gles3.glVertexAttribPointer
import platform.gles3.glViewport

/**
 * Kgl over the iOS OpenGLES.framework ES 3.0 bindings (`platform.gles3.*`). The class is the
 * iOS counterpart of [androidMain.AndroidKgl] / [jvmMain.JoglKgl] — a thin marshalling layer
 * that pins Kotlin arrays for the duration of each gl call and converts our `Int`-typed
 * enum values to `GLenum` (`UInt`).
 *
 * GLES 3.0 is the floor: Apple deprecated OpenGL ES on iOS 12 but the framework still
 * works. A Metal port could later sit behind this same Kgl interface.
 */
class IosKgl : Kgl {
    override val hasMaliOOMBug: Boolean = false
    // Mirror Android: leave `glslVersion` empty so legacy attribute/varying shaders compile
    // as GLSL ES 1.00 against the ES 3 context. Setting `#version 300 es` here forces strict
    // ES 3.00 parsing and breaks every BasicShaderProgram-style shader (Apple's GLSL compiler
    // rejects `attribute`/`varying` under that version directive even on the simulator).
    override val glslVersion: String = ""
    override val glslVersion3: String = "#version 300 es\n"
    override val supportsMultisampleFBO: Boolean = true
    override val supportsSizedTextureFormats: Boolean = true

    // Every real-iOS GPU back to A7 advertises OES_texture_float_linear; probed lazily in
    // case a future runtime drops it. See [Kgl.supportsFloatTextureLinear].
    override val supportsFloatTextureLinear: Boolean by lazy {
        // glGetString returns CPointer<GLubyteVar>; reinterpret to ByteVar for toKString.
        val exts = glGetString(GL_EXTENSIONS.toUInt())?.reinterpret<ByteVar>()?.toKString().orEmpty()
        "GL_OES_texture_float_linear" in exts || "GL_EXT_texture_float_linear" in exts
    }

    /**
     * Probed lazily on first access: builds a tiny RGBA32F FBO, then a tiny RGBA16F FBO,
     * and checks `glCheckFramebufferStatus`. iOS GLES3 on real devices typically supports
     * both; iOS Simulator on Mac silicon (Metal-backed) often supports only RGBA16F. The
     * result is cached so the probe runs at most once per [IosKgl] instance, after the
     * GL context is current (see [WorldWindow.eaglContext]).
     */
    override val maxRenderableFloatBits: Int by lazy {
        when {
            isFloatColorBufferRenderable(GL_RGBA32F.toUInt(), GL_FLOAT.toUInt()) -> 32
            isFloatColorBufferRenderable(GL_RGBA16F.toUInt(), GL_HALF_FLOAT.toUInt()) -> 16
            else -> 0
        }
    }

    private fun isFloatColorBufferRenderable(internalFormat: UInt, type: UInt): Boolean = memScoped {
        val texVar = alloc<UIntVar>(); glGenTextures(1, texVar.ptr)
        val tex = texVar.value
        val fbVar = alloc<UIntVar>(); glGenFramebuffers(1, fbVar.ptr)
        val fb = fbVar.value
        try {
            glBindTexture(GL_TEXTURE_2D.toUInt(), tex)
            glTexImage2D(GL_TEXTURE_2D.toUInt(), 0, internalFormat.toInt(), 1, 1, 0, GL_RGBA.toUInt(), type, null)
            glBindFramebuffer(GL_FRAMEBUFFER.toUInt(), fb)
            glFramebufferTexture2D(GL_FRAMEBUFFER.toUInt(), GL_COLOR_ATTACHMENT0.toUInt(), GL_TEXTURE_2D.toUInt(), tex, 0)
            val status = glCheckFramebufferStatus(GL_FRAMEBUFFER.toUInt())
            // Drain any error from the probe so it doesn't pollute the next real GL call.
            while (glGetError() != GL_NO_ERROR.toUInt()) {}
            status == GL_FRAMEBUFFER_COMPLETE.toUInt()
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER.toUInt(), 0u)
            glBindTexture(GL_TEXTURE_2D.toUInt(), 0u)
            fbVar.value = fb; glDeleteFramebuffers(1, fbVar.ptr)
            texVar.value = tex; glDeleteTextures(1, texVar.ptr)
        }
    }

    /**
     * iOS GLES has no "default framebuffer 0" — every render target must be a real FBO.
     * The host [earth.worldwind.WorldWindow] builds a framebuffer backed by its CAEAGLLayer
     * drawable and writes its id here. When the engine binds `KglFramebuffer.NONE` (id=0)
     * to "go back to the screen", we remap to this id instead.
     */
    var defaultFramebuffer: UInt = 0u

    override fun getParameteri(pname: Int): Int = memScoped {
        val v = alloc<IntVar>()
        glGetIntegerv(pname.toUInt(), v.ptr)
        v.value
    }

    override fun getParameterf(pname: Int): Float = memScoped {
        val v = alloc<FloatVar>()
        glGetFloatv(pname.toUInt(), v.ptr)
        v.value
    }

    override fun getParameteriv(pname: Int): IntArray {
        val out = IntArray(16)
        out.usePinned { glGetIntegerv(pname.toUInt(), it.addressOf(0)) }
        return out
    }

    override fun getParameterfv(pname: Int): FloatArray {
        val out = FloatArray(16)
        out.usePinned { glGetFloatv(pname.toUInt(), it.addressOf(0)) }
        return out
    }

    override fun createShader(type: Int): KglShader = KglShader(glCreateShader(type.toUInt()))

    override fun shaderSource(shader: KglShader, source: String) {
        memScoped {
            val cstr: CPointer<ByteVar> = source.cstr.ptr
            val arr = allocArrayOf(cstr)
            glShaderSource(shader.id, 1, arr, null)
        }
    }

    override fun compileShader(shader: KglShader) { glCompileShader(shader.id) }
    override fun deleteShader(shader: KglShader) { glDeleteShader(shader.id) }

    override fun getShaderParameteri(shader: KglShader, pname: Int): Int = memScoped {
        val v = alloc<IntVar>()
        glGetShaderiv(shader.id, pname.toUInt(), v.ptr)
        v.value
    }

    override fun getProgramInfoLog(program: KglProgram): String = memScoped {
        val len = alloc<IntVar>()
        val buf = allocArray<ByteVar>(LOG_BUF_SIZE)
        glGetProgramInfoLog(program.id, LOG_BUF_SIZE, len.ptr, buf)
        buf.toKString()
    }

    override fun getShaderInfoLog(shader: KglShader): String = memScoped {
        val len = alloc<IntVar>()
        val buf = allocArray<ByteVar>(LOG_BUF_SIZE)
        glGetShaderInfoLog(shader.id, LOG_BUF_SIZE, len.ptr, buf)
        buf.toKString()
    }

    override fun createProgram() = KglProgram(glCreateProgram())
    override fun deleteProgram(program: KglProgram) { glDeleteProgram(program.id) }
    override fun attachShader(program: KglProgram, shader: KglShader) { glAttachShader(program.id, shader.id) }
    override fun linkProgram(program: KglProgram) { glLinkProgram(program.id) }
    override fun useProgram(program: KglProgram) { glUseProgram(program.id) }

    override fun getProgramParameteri(program: KglProgram, pname: Int): Int = memScoped {
        val v = alloc<IntVar>()
        glGetProgramiv(program.id, pname.toUInt(), v.ptr)
        v.value
    }

    override fun getUniformLocation(program: KglProgram, name: String): KglUniformLocation =
        KglUniformLocation(glGetUniformLocation(program.id, name))

    override fun bindAttribLocation(program: KglProgram, index: Int, name: String) {
        glBindAttribLocation(program.id, index.toUInt(), name)
    }

    override fun enable(cap: Int) { glEnable(cap.toUInt()) }
    override fun disable(cap: Int) { glDisable(cap.toUInt()) }

    override fun enableVertexAttribArray(location: Int) { glEnableVertexAttribArray(location.toUInt()) }
    override fun disableVertexAttribArray(location: Int) { glDisableVertexAttribArray(location.toUInt()) }

    /** Allocate one GL name from a `glGen*(GLsizei n, GLuint* names)` API. */
    private inline fun genName(gen: (Int, CPointer<UIntVar>) -> Unit): UInt = memScoped {
        val v = alloc<UIntVar>()
        gen(1, v.ptr)
        v.value
    }

    /** Free one GL name via a `glDelete*(GLsizei n, const GLuint* names)` API. No-op for id 0. */
    private inline fun deleteName(id: UInt, del: (Int, CPointer<UIntVar>) -> Unit) {
        if (id == 0u) return
        memScoped {
            val v = alloc<UIntVar>().apply { value = id }
            del(1, v.ptr)
        }
    }

    override fun createBuffer() = KglBuffer(genName { n, p -> glGenBuffers(n, p) })

    override fun bindBuffer(target: Int, buffer: KglBuffer) { glBindBuffer(target.toUInt(), buffer.id) }

    override fun bufferData(target: Int, size: Int, sourceData: ShortArray?, usage: Int, offset: Int) {
        if (sourceData == null) glBufferData(target.toUInt(), size.toLong(), null, usage.toUInt())
        else sourceData.usePinned { glBufferData(target.toUInt(), size.toLong(), it.addressOf(offset), usage.toUInt()) }
    }

    override fun bufferData(target: Int, size: Int, sourceData: IntArray?, usage: Int, offset: Int) {
        if (sourceData == null) glBufferData(target.toUInt(), size.toLong(), null, usage.toUInt())
        else sourceData.usePinned { glBufferData(target.toUInt(), size.toLong(), it.addressOf(offset), usage.toUInt()) }
    }

    override fun bufferData(target: Int, size: Int, sourceData: FloatArray?, usage: Int, offset: Int) {
        if (sourceData == null) glBufferData(target.toUInt(), size.toLong(), null, usage.toUInt())
        else sourceData.usePinned { glBufferData(target.toUInt(), size.toLong(), it.addressOf(offset), usage.toUInt()) }
    }

    override fun bufferData(target: Int, size: Int, sourceData: ByteArray?, usage: Int, offset: Int) {
        if (sourceData == null) glBufferData(target.toUInt(), size.toLong(), null, usage.toUInt())
        else sourceData.usePinned { glBufferData(target.toUInt(), size.toLong(), it.addressOf(offset), usage.toUInt()) }
    }

    override fun bufferData(target: Int, size: Int, usage: Int) {
        glBufferData(target.toUInt(), size.toLong(), null, usage.toUInt())
    }

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: ShortArray) {
        sourceData.usePinned { glBufferSubData(target.toUInt(), offset.toLong(), size.toLong(), it.addressOf(0)) }
    }

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: IntArray) {
        sourceData.usePinned { glBufferSubData(target.toUInt(), offset.toLong(), size.toLong(), it.addressOf(0)) }
    }

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: FloatArray) {
        sourceData.usePinned { glBufferSubData(target.toUInt(), offset.toLong(), size.toLong(), it.addressOf(0)) }
    }

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: ByteArray) {
        sourceData.usePinned { glBufferSubData(target.toUInt(), offset.toLong(), size.toLong(), it.addressOf(0)) }
    }

    override fun mapAndCopyBufferRange(
        target: Int, offset: Int, length: Int, source: ByteArray, srcOffset: Int, access: Int
    ) {
        val mapped = glMapBufferRange(target.toUInt(), offset.toLong(), length.toLong(), access.toUInt())
        if (mapped != null) {
            source.usePinned { src ->
                memcpy(__dst = mapped, __src = src.addressOf(srcOffset), __n = length.toULong())
            }
            glUnmapBuffer(target.toUInt())
        } else {
            // Fall back to bufferSubData on drivers that reject the access combo.
            source.usePinned {
                glBufferSubData(target.toUInt(), offset.toLong(), length.toLong(), it.addressOf(srcOffset))
            }
        }
    }

    override fun deleteBuffer(buffer: KglBuffer) =
        deleteName(buffer.id) { n, p -> glDeleteBuffers(n, p) }

    override fun vertexAttribPointer(
        location: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int
    ) {
        glVertexAttribPointer(
            location.toUInt(), size, type.toUInt(),
            if (normalized) 1u else 0u, stride, offset.toLong().toCPointer<ByteVar>()
        )
    }

    override fun uniform1f(location: KglUniformLocation, f: Float) { glUniform1f(location.id, f) }
    override fun uniform1fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) {
        value.usePinned { glUniform1fv(location.id, count, it.addressOf(offset)) }
    }
    override fun uniform1i(location: KglUniformLocation, i: Int) { glUniform1i(location.id, i) }

    override fun uniform2f(location: KglUniformLocation, x: Float, y: Float) { glUniform2f(location.id, x, y) }
    override fun uniform2fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) {
        value.usePinned { glUniform2fv(location.id, count, it.addressOf(offset)) }
    }
    override fun uniform2i(location: KglUniformLocation, x: Int, y: Int) { glUniform2i(location.id, x, y) }

    override fun uniform3f(location: KglUniformLocation, x: Float, y: Float, z: Float) { glUniform3f(location.id, x, y, z) }
    override fun uniform3fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) {
        value.usePinned { glUniform3fv(location.id, count, it.addressOf(offset)) }
    }
    override fun uniform3i(location: KglUniformLocation, x: Int, y: Int, z: Int) { glUniform3i(location.id, x, y, z) }

    override fun uniform4f(location: KglUniformLocation, x: Float, y: Float, z: Float, w: Float) {
        glUniform4f(location.id, x, y, z, w)
    }
    override fun uniform4fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) {
        value.usePinned { glUniform4fv(location.id, count, it.addressOf(offset)) }
    }
    override fun uniform4i(location: KglUniformLocation, x: Int, y: Int, z: Int, w: Int) {
        glUniform4i(location.id, x, y, z, w)
    }

    override fun uniformMatrix3fv(location: KglUniformLocation, count: Int, transpose: Boolean, value: FloatArray, offset: Int) {
        value.usePinned {
            glUniformMatrix3fv(location.id, count, if (transpose) 1u else 0u, it.addressOf(offset))
        }
    }

    override fun uniformMatrix4fv(location: KglUniformLocation, count: Int, transpose: Boolean, value: FloatArray, offset: Int) {
        value.usePinned {
            glUniformMatrix4fv(location.id, count, if (transpose) 1u else 0u, it.addressOf(offset))
        }
    }

    override fun cullFace(mode: Int) { glCullFace(mode.toUInt()) }
    override fun frontFace(mode: Int) { glFrontFace(mode.toUInt()) }
    override fun polygonOffset(factor: Float, units: Float) { glPolygonOffset(factor, units) }
    override fun depthFunc(func: Int) { glDepthFunc(func.toUInt()) }
    override fun depthMask(mask: Boolean) { glDepthMask(if (mask) 1u else 0u) }
    override fun blendFunc(sFactor: Int, dFactor: Int) { glBlendFunc(sFactor.toUInt(), dFactor.toUInt()) }
    override fun viewport(x: Int, y: Int, width: Int, height: Int) { glViewport(x, y, width, height) }
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) { glClearColor(r, g, b, a) }
    override fun clear(mask: Int) { glClear(mask.toUInt()) }

    override fun createTexture() = KglTexture(genName { n, p -> glGenTextures(n, p) })
    override fun deleteTexture(texture: KglTexture) =
        deleteName(texture.id) { n, p -> glDeleteTextures(n, p) }

    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, format: Int, type: Int, buffer: ByteArray?
    ) {
        if (buffer == null) {
            glTexImage2D(target.toUInt(), level, internalFormat, width, height, border, format.toUInt(), type.toUInt(), null)
        } else {
            buffer.usePinned {
                glTexImage2D(
                    target.toUInt(), level, internalFormat, width, height, border,
                    format.toUInt(), type.toUInt(), it.addressOf(0)
                )
            }
        }
    }

    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, format: Int, type: Int, buffer: FloatArray?
    ) {
        if (buffer == null) {
            glTexImage2D(target.toUInt(), level, internalFormat, width, height, border, format.toUInt(), type.toUInt(), null)
        } else {
            buffer.usePinned {
                glTexImage2D(
                    target.toUInt(), level, internalFormat, width, height, border,
                    format.toUInt(), type.toUInt(), it.addressOf(0)
                )
            }
        }
    }

    override fun texSubImage2D(
        target: Int, level: Int, xoffset: Int, yoffset: Int, width: Int, height: Int,
        format: Int, type: Int, buffer: ByteArray?
    ) {
        if (buffer == null) {
            glTexSubImage2D(target.toUInt(), level, xoffset, yoffset, width, height, format.toUInt(), type.toUInt(), null)
        } else {
            buffer.usePinned {
                glTexSubImage2D(
                    target.toUInt(), level, xoffset, yoffset, width, height,
                    format.toUInt(), type.toUInt(), it.addressOf(0)
                )
            }
        }
    }

    override fun texSubImage2D(
        target: Int, level: Int, xoffset: Int, yoffset: Int, width: Int, height: Int,
        format: Int, type: Int, offset: Int
    ) {
        // PBO path: when GL_PIXEL_UNPACK_BUFFER is bound, the last arg is interpreted as a
        // byte offset into the bound buffer object. Pack the offset into a fake pointer.
        glTexSubImage2D(
            target.toUInt(), level, xoffset, yoffset, width, height,
            format.toUInt(), type.toUInt(), offset.toLong().toCPointer<ByteVar>()
        )
    }

    override fun activeTexture(texture: Int) { glActiveTexture(texture.toUInt()) }
    override fun bindTexture(target: Int, texture: KglTexture) { glBindTexture(target.toUInt(), texture.id) }
    override fun generateMipmap(target: Int) { glGenerateMipmap(target.toUInt()) }
    override fun texParameteri(target: Int, pname: Int, value: Int) { glTexParameteri(target.toUInt(), pname.toUInt(), value) }

    override fun drawArrays(mode: Int, first: Int, count: Int) { glDrawArrays(mode.toUInt(), first, count) }
    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) {
        glDrawElements(mode.toUInt(), count, type.toUInt(), offset.toLong().toCPointer<ByteVar>())
    }

    override fun getError(): Int = glGetError().toInt()
    override fun finish() { glFinish() }

    override fun bindFramebuffer(target: Int, framebuffer: KglFramebuffer) {
        // Remap engine "default" (id=0) to the WorldWindow-owned screen framebuffer.
        val id = if (framebuffer.id == 0u) defaultFramebuffer else framebuffer.id
        glBindFramebuffer(target.toUInt(), id)
    }
    override fun createFramebuffer() = KglFramebuffer(genName { n, p -> glGenFramebuffers(n, p) })
    override fun deleteFramebuffer(framebuffer: KglFramebuffer) =
        deleteName(framebuffer.id) { n, p -> glDeleteFramebuffers(n, p) }
    override fun checkFramebufferStatus(target: Int): Int = glCheckFramebufferStatus(target.toUInt()).toInt()

    override fun framebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: KglTexture, level: Int) {
        glFramebufferTexture2D(target.toUInt(), attachment.toUInt(), textarget.toUInt(), texture.id, level)
    }

    override fun createRenderbuffer() = KglRenderbuffer(genName { n, p -> glGenRenderbuffers(n, p) })
    override fun deleteRenderbuffer(renderbuffer: KglRenderbuffer) =
        deleteName(renderbuffer.id) { n, p -> glDeleteRenderbuffers(n, p) }
    override fun bindRenderbuffer(target: Int, renderbuffer: KglRenderbuffer) {
        glBindRenderbuffer(target.toUInt(), renderbuffer.id)
    }
    override fun renderbufferStorageMultisample(target: Int, samples: Int, internalFormat: Int, width: Int, height: Int) {
        glRenderbufferStorageMultisample(target.toUInt(), samples, internalFormat.toUInt(), width, height)
    }
    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: KglRenderbuffer) {
        glFramebufferRenderbuffer(target.toUInt(), attachment.toUInt(), renderbufferTarget.toUInt(), renderbuffer.id)
    }
    override fun blitFramebuffer(
        srcX0: Int, srcY0: Int, srcX1: Int, srcY1: Int,
        dstX0: Int, dstY0: Int, dstX1: Int, dstY1: Int,
        mask: Int, filter: Int
    ) {
        glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask.toUInt(), filter.toUInt())
    }

    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, buffer: ByteArray) {
        buffer.usePinned { glReadPixels(x, y, width, height, format.toUInt(), type.toUInt(), it.addressOf(0)) }
    }

    override fun readPixelsToBuffer(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, offset: Int) {
        // PBO target: last arg is byte offset into GL_PIXEL_PACK_BUFFER; pack as fake pointer.
        glReadPixels(x, y, width, height, format.toUInt(), type.toUInt(), offset.toLong().toCPointer<ByteVar>())
    }

    override fun getBufferSubData(target: Int, srcOffset: Int, dst: ByteArray) {
        // GLES 3.0 has glMapBufferRange but not glGetBufferSubData (3.2+); map READ, copy, unmap.
        val mapped = glMapBufferRange(target.toUInt(), srcOffset.toLong(), dst.size.toLong(), GL_MAP_READ_BIT.toUInt())
        if (mapped != null) {
            dst.usePinned { d ->
                memcpy(__dst = d.addressOf(0), __src = mapped, __n = dst.size.toULong())
            }
            glUnmapBuffer(target.toUInt())
        }
    }

    override fun fenceSync(): KglSync {
        val sync = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE.toUInt(), 0u) ?: return KglSync.NONE
        return KglSync(sync.rawValue.toLong())
    }

    override fun isSyncSignalled(sync: KglSync): Boolean {
        if (!sync.isValid()) return false
        val ptr: CPointer<__GLsync> = sync.id.toCPointer() ?: return false
        val result = glClientWaitSync(ptr, 0u, 0u)
        return result == GL_ALREADY_SIGNALED.toUInt() || result == GL_CONDITION_SATISFIED.toUInt()
    }

    override fun deleteSync(sync: KglSync) {
        if (!sync.isValid()) return
        val ptr: CPointer<__GLsync> = sync.id.toCPointer() ?: return
        glDeleteSync(ptr)
    }

    override fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean) {
        glColorMask(if (r) 1u else 0u, if (g) 1u else 0u, if (b) 1u else 0u, if (a) 1u else 0u)
    }

    override fun lineWidth(width: Float) { glLineWidth(width) }
    override fun pixelStorei(pname: Int, param: Int) { glPixelStorei(pname.toUInt(), param) }

    private companion object {
        const val LOG_BUF_SIZE = 1024
    }
}
