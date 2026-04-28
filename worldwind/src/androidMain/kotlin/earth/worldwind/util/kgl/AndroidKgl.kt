package earth.worldwind.util.kgl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer

class AndroidKgl : Kgl {
    private val arrI = IntArray(16)
    private val arrF = FloatArray(16)

    override val hasMaliOOMBug: Boolean by lazy {
        GLES20.glGetString(GL_RENDERER).contains("mali", true)
    }
    override val glslVersion3 get() = if (isGles3OrLater) "#version 300 es\n" else ""

    // RenderResourceCache requests a GLES3 EGL context and falls back to GLES2 if the device
    // doesn't have it. The "OpenGL ES X.Y …" version string tells us which we got — MSAA
    // renderbuffers, glBlitFramebuffer and sized internal formats all require GLES 3.0+.
    private val isGles3OrLater: Boolean by lazy {
        val ver = GLES20.glGetString(GLES20.GL_VERSION) ?: return@lazy false
        val match = Regex("""OpenGL ES (\d+)""").find(ver) ?: return@lazy false
        (match.groupValues[1].toIntOrNull() ?: 0) >= 3
    }
    override val supportsMultisampleFBO get() = isGles3OrLater
    override val supportsSizedTextureFormats get() = isGles3OrLater

    override fun getParameteri(pname: Int): Int {
        GLES20.glGetIntegerv(pname, arrI, 0)
        return arrI[0]
    }

    override fun getParameterf(pname: Int): Float {
        GLES20.glGetFloatv(pname, arrF, 0)
        return arrF[0]
    }

    override fun getParameteriv(pname: Int): IntArray {
        GLES20.glGetIntegerv(pname, arrI, 0)
        return arrI
    }

    override fun getParameterfv(pname: Int): FloatArray {
        GLES20.glGetFloatv(pname, arrF, 0)
        return arrF
    }

    override fun createShader(type: Int): KglShader = KglShader(GLES20.glCreateShader(type))

    override fun shaderSource(shader: KglShader, source: String) = GLES20.glShaderSource(shader.id, source)

    override fun compileShader(shader: KglShader) = GLES20.glCompileShader(shader.id)

    override fun deleteShader(shader: KglShader) = GLES20.glDeleteShader(shader.id)

    override fun getShaderParameteri(shader: KglShader, pname: Int): Int {
        GLES20.glGetShaderiv(shader.id, pname, arrI, 0)
        return arrI[0]
    }

    override fun getProgramInfoLog(program: KglProgram): String = GLES20.glGetProgramInfoLog(program.id)

    override fun getShaderInfoLog(shader: KglShader): String = GLES20.glGetShaderInfoLog(shader.id)

    override fun createProgram() = KglProgram(GLES20.glCreateProgram())

    override fun deleteProgram(program: KglProgram) = GLES20.glDeleteShader(program.id)

    override fun attachShader(program: KglProgram, shader: KglShader) = GLES20.glAttachShader(program.id, shader.id)

    override fun linkProgram(program: KglProgram) = GLES20.glLinkProgram(program.id)

    override fun useProgram(program: KglProgram) = GLES20.glUseProgram(program.id)

    override fun getProgramParameteri(program: KglProgram, pname: Int): Int {
        GLES20.glGetProgramiv(program.id, pname, arrI, 0)
        return arrI[0]
    }

    override fun getUniformLocation(program: KglProgram, name: String): KglUniformLocation =
        KglUniformLocation(GLES20.glGetUniformLocation(program.id, name))

    override fun bindAttribLocation(program: KglProgram, index: Int, name: String) =
        GLES20.glBindAttribLocation(program.id, index, name)

    override fun createBuffer(): KglBuffer {
        GLES20.glGenBuffers(1, arrI, 0)
        return KglBuffer(arrI[0])
    }

    override fun bindBuffer(target: Int, buffer: KglBuffer) = GLES20.glBindBuffer(target, buffer.id)

    override fun bufferData(target: Int, size: Int, sourceData: ShortArray?, usage: Int, offset: Int) =
        GLES20.glBufferData(target, size, sourceData?.let { ShortBuffer.wrap(it, offset, size / 2) }, usage)

    override fun bufferData(target: Int, size: Int, sourceData: IntArray?, usage: Int, offset: Int) =
        GLES20.glBufferData(target, size, sourceData?.let { IntBuffer.wrap(it, offset, size / 4) }, usage)

    override fun bufferData(target: Int, size: Int, sourceData: FloatArray?, usage: Int, offset: Int) =
        GLES20.glBufferData(target, size, sourceData?.let { FloatBuffer.wrap(it, offset, size / 4) }, usage)

    override fun bufferData(target: Int, size: Int, sourceData: ByteArray?, usage: Int, offset: Int) =
        GLES20.glBufferData(target, size, sourceData?.let { ByteBuffer.wrap(it, offset, size) }, usage)

    override fun bufferData(target: Int, size: Int, usage: Int) =
        GLES20.glBufferData(target, size, null, usage)

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: ShortArray) =
        GLES20.glBufferSubData(target, offset, size, ShortBuffer.wrap(sourceData, 0, size / 2))

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: IntArray) =
        GLES20.glBufferSubData(target, offset, size, IntBuffer.wrap(sourceData, 0, size / 4))

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: FloatArray) =
        GLES20.glBufferSubData(target, offset, size, FloatBuffer.wrap(sourceData, 0, size / 4))

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: ByteArray) =
        GLES20.glBufferSubData(target, offset, size, ByteBuffer.wrap(sourceData, 0, size))

    override fun mapAndCopyBufferRange(
        target: Int, offset: Int, length: Int, source: ByteArray, srcOffset: Int, access: Int
    ) {
        // GLES30.glMapBufferRange returns java.nio.Buffer; cast to ByteBuffer (it always is
        // for byte-sized targets like GL_PIXEL_UNPACK_BUFFER). Falls through to the
        // bufferSubData path if the driver returns null (rare; bad access combo).
        val mapped = GLES30.glMapBufferRange(target, offset, length, access) as? ByteBuffer
        if (mapped != null) {
            mapped.put(source, srcOffset, length)
            GLES30.glUnmapBuffer(target)
        } else {
            GLES20.glBufferSubData(target, offset, length, ByteBuffer.wrap(source, srcOffset, length))
        }
    }

    override fun deleteBuffer(buffer: KglBuffer) {
        arrI[0] = buffer.id
        GLES20.glDeleteBuffers(1, arrI, 0)
    }

    override fun vertexAttribPointer(
        location: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int
    ) = GLES20.glVertexAttribPointer(location, size, type, normalized, stride, offset)

    override fun enableVertexAttribArray(location: Int) = GLES20.glEnableVertexAttribArray(location)

    override fun disableVertexAttribArray(location: Int) = GLES20.glDisableVertexAttribArray(location)

    override fun enable(cap: Int) = GLES20.glEnable(cap)

    override fun disable(cap: Int) = GLES20.glDisable(cap)

    override fun uniform1f(location: KglUniformLocation, f: Float) =
        GLES20.glUniform1f(location.id, f)

    override fun uniform1fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) =
        GLES20.glUniform1fv(location.id, count, value, offset)

    override fun uniform1i(location: KglUniformLocation, i: Int) =
        GLES20.glUniform1i(location.id, i)

    override fun uniform2f(location: KglUniformLocation, x: Float, y: Float) =
        GLES20.glUniform2f(location.id, x, y)

    override fun uniform2fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) =
        GLES20.glUniform2fv(location.id, count, value, offset)

    override fun uniform2i(location: KglUniformLocation, x: Int, y: Int) =
        GLES20.glUniform2i(location.id, x, y)

    override fun uniform3f(location: KglUniformLocation, x: Float, y: Float, z: Float) =
        GLES20.glUniform3f(location.id, x, y, z)

    override fun uniform3fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) =
        GLES20.glUniform3fv(location.id, count, value, offset)

    override fun uniform3i(location: KglUniformLocation, x: Int, y: Int, z: Int) =
        GLES20.glUniform3i(location.id, x, y, z)

    override fun uniform4f(location: KglUniformLocation, x: Float, y: Float, z: Float, w: Float) =
        GLES20.glUniform4f(location.id, x, y, z, w)

    override fun uniform4fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) =
        GLES20.glUniform4fv(location.id, count, value, offset)

    override fun uniform4i(location: KglUniformLocation, x: Int, y: Int, z: Int, w: Int) =
        GLES20.glUniform4i(location.id, x, y, z, w)

    override fun uniformMatrix3fv(location: KglUniformLocation, count: Int, transpose: Boolean, value: FloatArray, offset: Int) =
        GLES20.glUniformMatrix3fv(location.id, count, transpose, value, offset)

    override fun uniformMatrix4fv(location: KglUniformLocation, count: Int, transpose: Boolean, value: FloatArray, offset: Int) =
        GLES20.glUniformMatrix4fv(location.id, count, transpose, value, offset)

    override fun lineWidth(width: Float) = GLES20.glLineWidth(width)

    override fun polygonOffset(factor: Float, units: Float) = GLES20.glPolygonOffset(factor, units)

    override fun cullFace(mode: Int) = GLES20.glCullFace(mode)

    override fun frontFace(mode: Int) = GLES20.glFrontFace(mode)

    override fun depthFunc(func: Int) = GLES20.glDepthFunc(func)

    override fun depthMask(mask: Boolean) = GLES20.glDepthMask(mask)

    override fun blendFunc(sFactor: Int, dFactor: Int) = GLES20.glBlendFunc(sFactor, dFactor)

    override fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean) = GLES20.glColorMask(r, g, b, a)

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = GLES20.glViewport(x, y, width, height)

    override fun clear(mask: Int) = GLES20.glClear(mask)

    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = GLES20.glClearColor(r, g, b, a)

    override fun createTexture(): KglTexture {
        GLES20.glGenTextures(1, arrI, 0)
        return KglTexture(arrI[0])
    }

    override fun deleteTexture(texture: KglTexture) {
        arrI[0] = texture.id
        GLES20.glDeleteTextures(1, arrI, 0)
    }

    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, buffer: ByteArray?
    ) = GLES20.glTexImage2D(target, level, internalFormat, width, height, border, format, type, buffer?.let { ByteBuffer.wrap(it) })

    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, buffer: FloatArray?
    ) = GLES20.glTexImage2D(target, level, internalFormat, width, height, border, format, type, buffer?.let { FloatBuffer.wrap(it) })

    override fun texSubImage2D(
        target: Int, level: Int, xoffset: Int, yoffset: Int, width: Int, height: Int, format: Int, type: Int, buffer: ByteArray?
    ) = GLES20.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, buffer?.let { ByteBuffer.wrap(it) })

    override fun texSubImage2D(
        target: Int, level: Int, xoffset: Int, yoffset: Int, width: Int, height: Int, format: Int, type: Int, offset: Int
    ) {
        // Android's GLES30 binding doesn't expose a `glTexSubImage2D(...int offset)` variant
        // for 2D textures (only the 3D one and the Buffer-typed 2D variant exist). When
        // GL_PIXEL_UNPACK_BUFFER is bound, calling the Buffer variant with `null` reads from
        // the PBO at byte offset 0 — sufficient for our single-buffered video upload path.
        // Non-zero offsets aren't exposed; callers needing them must use the JVM/desktop
        // path or seek the PBO with bufferSubData beforehand.
        require(offset == 0) { "AndroidKgl.texSubImage2D PBO path supports only offset=0" }
        GLES30.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, null as java.nio.Buffer?)
    }

    override fun activeTexture(texture: Int) = GLES20.glActiveTexture(texture)

    override fun bindTexture(target: Int, texture: KglTexture) = GLES20.glBindTexture(target, texture.id)

    override fun generateMipmap(target: Int) = GLES20.glGenerateMipmap(target)

    override fun texParameteri(target: Int, pname: Int, value: Int) = GLES20.glTexParameteri(target, pname, value)

    override fun drawArrays(mode: Int, first: Int, count: Int) = GLES20.glDrawArrays(mode, first, count)

    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) = GLES20.glDrawElements(mode, count, type, offset)

    override fun getError() = GLES20.glGetError()

    override fun finish() = GLES20.glFinish()

    override fun bindFramebuffer(target: Int, framebuffer: KglFramebuffer) = GLES20.glBindFramebuffer(target, framebuffer.id)

    override fun createFramebuffer(): KglFramebuffer {
        GLES20.glGenFramebuffers(1, arrI, 0)
        return KglFramebuffer(arrI[0])
    }

    override fun deleteFramebuffer(framebuffer: KglFramebuffer) {
        arrI[0] = framebuffer.id
        GLES20.glDeleteFramebuffers(1, arrI, 0)
    }

    override fun checkFramebufferStatus(target: Int) = GLES20.glCheckFramebufferStatus(target)

    override fun framebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: KglTexture, level: Int) =
        GLES20.glFramebufferTexture2D(target, attachment, textarget, texture.id, level)

    override fun createRenderbuffer(): KglRenderbuffer {
        GLES20.glGenRenderbuffers(1, arrI, 0)
        return KglRenderbuffer(arrI[0])
    }
    override fun deleteRenderbuffer(renderbuffer: KglRenderbuffer) {
        arrI[0] = renderbuffer.id
        GLES20.glDeleteRenderbuffers(1, arrI, 0)
    }
    override fun bindRenderbuffer(target: Int, renderbuffer: KglRenderbuffer) =
        GLES20.glBindRenderbuffer(target, renderbuffer.id)
    override fun renderbufferStorageMultisample(target: Int, samples: Int, internalFormat: Int, width: Int, height: Int) =
        GLES30.glRenderbufferStorageMultisample(target, samples, internalFormat, width, height)
    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: KglRenderbuffer) =
        GLES20.glFramebufferRenderbuffer(target, attachment, renderbufferTarget, renderbuffer.id)
    override fun blitFramebuffer(
        srcX0: Int, srcY0: Int, srcX1: Int, srcY1: Int,
        dstX0: Int, dstY0: Int, dstX1: Int, dstY1: Int,
        mask: Int, filter: Int
    ) = GLES30.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter)

    override fun readPixels(
        x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, buffer: ByteArray
    ) = GLES20.glReadPixels(x, y, width, height, format, type, ByteBuffer.wrap(buffer))

    // GLES3 PBO-target readPixels: when GL_PIXEL_PACK_BUFFER is bound, the [offset] arg is
    // interpreted as a byte offset into that buffer. Non-blocking on the CPU side.
    override fun readPixelsToBuffer(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, offset: Int) =
        GLES30.glReadPixels(x, y, width, height, format, type, offset)

    // GLES 3.0 has glMapBufferRange but not glGetBufferSubData (added only in GLES 3.2). Map
    // the requested sub-range read-only, copy into the target ByteArray, then unmap.
    override fun getBufferSubData(target: Int, srcOffset: Int, dst: ByteArray) {
        val mapped = GLES30.glMapBufferRange(target, srcOffset, dst.size, GL_MAP_READ_BIT) as? ByteBuffer
        if (mapped != null) {
            mapped.get(dst)
            GLES30.glUnmapBuffer(target)
        }
    }

    override fun fenceSync() = KglSync(GLES30.glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0).toLong())

    override fun isSyncSignalled(sync: KglSync): Boolean {
        if (!sync.isValid()) return false
        // 0 timeout + no flush: returns immediately with status. Matches the WebGL2 contract.
        val result = GLES30.glClientWaitSync(sync.id, 0, 0)
        return result == GL_ALREADY_SIGNALED || result == GL_CONDITION_SATISFIED
    }

    override fun deleteSync(sync: KglSync) {
        if (sync.isValid()) GLES30.glDeleteSync(sync.id)
    }

    override fun pixelStorei(pname: Int, param: Int) = GLES20.glPixelStorei(pname, param)
}
