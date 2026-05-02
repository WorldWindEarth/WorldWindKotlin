package earth.worldwind.util.kgl

import org.lwjgl.opengl.GL33
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer

class LwjglKgl : Kgl {
    private val arrI = IntArray(16)
    private val arrF = FloatArray(16)

    override val hasMaliOOMBug = false
    override val glslVersion3 = "#version 330 core\n"

    override fun getParameteri(pname: Int): Int {
        GL33.glGetIntegerv(pname, arrI)
        return arrI[0]
    }

    override fun getParameterf(pname: Int): Float {
        GL33.glGetFloatv(pname, arrF)
        return arrF[0]
    }

    override fun getParameteriv(pname: Int): IntArray {
        GL33.glGetIntegerv(pname, arrI)
        return arrI
    }

    override fun getParameterfv(pname: Int): FloatArray {
        GL33.glGetFloatv(pname, arrF)
        return arrF
    }

    override fun createShader(type: Int) = KglShader(GL33.glCreateShader(type))

    override fun shaderSource(shader: KglShader, source: String) = GL33.glShaderSource(shader.id, source)

    override fun compileShader(shader: KglShader) = GL33.glCompileShader(shader.id)

    override fun deleteShader(shader: KglShader) = GL33.glDeleteShader(shader.id)

    override fun getShaderParameteri(shader: KglShader, pname: Int): Int {
        GL33.glGetShaderiv(shader.id, pname, arrI)
        return arrI[0]
    }

    override fun getProgramInfoLog(program: KglProgram): String = GL33.glGetProgramInfoLog(program.id)

    override fun getShaderInfoLog(shader: KglShader): String = GL33.glGetShaderInfoLog(shader.id)

    override fun createProgram() = KglProgram(GL33.glCreateProgram())

    override fun deleteProgram(program: KglProgram) = GL33.glDeleteShader(program.id)

    override fun attachShader(program: KglProgram, shader: KglShader) = GL33.glAttachShader(program.id, shader.id)

    override fun linkProgram(program: KglProgram) = GL33.glLinkProgram(program.id)

    override fun useProgram(program: KglProgram) = GL33.glUseProgram(program.id)

    override fun getProgramParameteri(program: KglProgram, pname: Int): Int {
        GL33.glGetProgramiv(program.id, pname, arrI)
        return arrI[0]
    }

    override fun getUniformLocation(program: KglProgram, name: String) =
        KglUniformLocation(GL33.glGetUniformLocation(program.id, name))

    override fun bindAttribLocation(program: KglProgram, index: Int, name: String) =
        GL33.glBindAttribLocation(program.id, index, name)

    override fun createBuffer() = KglBuffer(GL33.glGenBuffers())

    override fun bindBuffer(target: Int, buffer: KglBuffer) = GL33.glBindBuffer(target, buffer.id)

    override fun bufferData(target: Int, size: Int, sourceData: ShortArray?, usage: Int, offset: Int) =
        if (sourceData != null) GL33.glBufferData(target, ShortBuffer.wrap(sourceData, offset, sourceData.size - offset), usage)
        else GL33.glBufferData(target, size.toLong(), usage)

    override fun bufferData(target: Int, size: Int, sourceData: IntArray?, usage: Int, offset: Int) =
        if (sourceData != null) GL33.glBufferData(target, IntBuffer.wrap(sourceData, offset, sourceData.size - offset), usage)
        else GL33.glBufferData(target, size.toLong(), usage)

    override fun bufferData(target: Int, size: Int, sourceData: FloatArray?, usage: Int, offset: Int) =
        if (sourceData != null) GL33.glBufferData(target, FloatBuffer.wrap(sourceData, offset, sourceData.size - offset), usage)
        else GL33.glBufferData(target, size.toLong(), usage)

    override fun bufferData(target: Int, size: Int, sourceData: ByteArray?, usage: Int, offset: Int) =
        if (sourceData != null) GL33.glBufferData(target, ByteBuffer.wrap(sourceData, offset, sourceData.size - offset), usage)
        else GL33.glBufferData(target, size.toLong(), usage)

    override fun bufferData(target: Int, size: Int, usage: Int) =
        GL33.glBufferData(target, size.toLong(), usage)

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: ShortArray) =
        GL33.glBufferSubData(target, offset.toLong(), ShortBuffer.wrap(sourceData, 0, size / 2))

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: IntArray) =
        GL33.glBufferSubData(target, offset.toLong(), IntBuffer.wrap(sourceData, 0, size / 4))

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: FloatArray) =
        GL33.glBufferSubData(target, offset.toLong(), FloatBuffer.wrap(sourceData, 0, size / 4))

    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: ByteArray) =
        GL33.glBufferSubData(target, offset.toLong(), ByteBuffer.wrap(sourceData, 0, size))

    override fun mapAndCopyBufferRange(
        target: Int, offset: Int, length: Int, source: ByteArray, srcOffset: Int, access: Int
    ) {
        // LWJGL's glMapBufferRange returns a nullable ByteBuffer. When mapping fails
        // (rare — usually unsupported access combo) the driver writes a GL_INVALID_*
        // error and returns null; fall back to glBufferSubData so the upload still
        // completes.
        val mapped: ByteBuffer? = GL33.glMapBufferRange(target, offset.toLong(), length.toLong(), access)
        if (mapped != null) {
            mapped.put(source, srcOffset, length)
            GL33.glUnmapBuffer(target)
        } else {
            GL33.glBufferSubData(target, offset.toLong(), ByteBuffer.wrap(source, srcOffset, length))
        }
    }

    override fun deleteBuffer(buffer: KglBuffer) = GL33.glDeleteBuffers(buffer.id)

    override fun vertexAttribPointer(
        location: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int
    ) = GL33.glVertexAttribPointer(location, size, type, normalized, stride, offset.toLong())

    override fun enableVertexAttribArray(location: Int) = GL33.glEnableVertexAttribArray(location)

    override fun disableVertexAttribArray(location: Int) = GL33.glDisableVertexAttribArray(location)

    override fun enable(cap: Int) = GL33.glEnable(cap)

    override fun disable(cap: Int) = GL33.glDisable(cap)

    override fun uniform1f(location: KglUniformLocation, f: Float) =
        GL33.glUniform1f(location.id, f)

    override fun uniform1fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) =
        GL33.glUniform1fv(location.id, value.sliceArray(offset until offset + count))

    override fun uniform1i(location: KglUniformLocation, i: Int) =
        GL33.glUniform1i(location.id, i)

    override fun uniform2f(location: KglUniformLocation, x: Float, y: Float) =
        GL33.glUniform2f(location.id, x, y)

    override fun uniform2fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) =
        GL33.glUniform2fv(location.id, value.sliceArray(offset until offset + count * 2))

    override fun uniform2i(location: KglUniformLocation, x: Int, y: Int) =
        GL33.glUniform2i(location.id, x, y)

    override fun uniform3f(location: KglUniformLocation, x: Float, y: Float, z: Float) =
        GL33.glUniform3f(location.id, x, y, z)

    override fun uniform3fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) =
        GL33.glUniform3fv(location.id, value.sliceArray(offset until offset + count * 3))

    override fun uniform3i(location: KglUniformLocation, x: Int, y: Int, z: Int) =
        GL33.glUniform3i(location.id, x, y, z)

    override fun uniform4f(location: KglUniformLocation, x: Float, y: Float, z: Float, w: Float) =
        GL33.glUniform4f(location.id, x, y, z, w)

    override fun uniform4fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) =
        GL33.glUniform4fv(location.id, value.sliceArray(offset until offset + count * 4))

    override fun uniform4i(location: KglUniformLocation, x: Int, y: Int, z: Int, w: Int) =
        GL33.glUniform4i(location.id, x, y, z, w)

    override fun uniformMatrix3fv(location: KglUniformLocation, count: Int, transpose: Boolean, value: FloatArray, offset: Int) =
        GL33.glUniformMatrix3fv(location.id, transpose, value.sliceArray(offset until offset + count * 12))

    override fun uniformMatrix4fv(location: KglUniformLocation, count: Int, transpose: Boolean, value: FloatArray, offset: Int) =
        GL33.glUniformMatrix4fv(location.id, transpose, value.sliceArray(offset until offset + count * 14))

    override fun lineWidth(width: Float) = GL33.glLineWidth(width)

    override fun polygonOffset(factor: Float, units: Float) = GL33.glPolygonOffset(factor, units)

    override fun cullFace(mode: Int) = GL33.glCullFace(mode)

    override fun frontFace(mode: Int) = GL33.glFrontFace(mode)

    override fun depthFunc(func: Int) = GL33.glDepthFunc(func)

    override fun depthMask(mask: Boolean) = GL33.glDepthMask(mask)

    override fun blendFunc(sFactor: Int, dFactor: Int) = GL33.glBlendFunc(sFactor, dFactor)

    override fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean) = GL33.glColorMask(r, g, b, a)

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = GL33.glViewport(x, y, width, height)

    override fun clear(mask: Int) = GL33.glClear(mask)

    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = GL33.glClearColor(r, g, b, a)

    override fun createTexture() = KglTexture(GL33.glGenTextures())

    override fun deleteTexture(texture: KglTexture) = GL33.glDeleteTextures(texture.id)

    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, buffer: ByteArray?
    ) = GL33.glTexImage2D(target, level, internalFormat, width, height, border, format, type, buffer?.let { ByteBuffer.wrap(it) })

    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, buffer: FloatArray?
    ) = GL33.glTexImage2D(target, level, internalFormat, width, height, border, format, type, buffer?.let { FloatBuffer.wrap(it) })

    override fun texSubImage2D(
        target: Int, level: Int, xoffset: Int, yoffset: Int, width: Int, height: Int, format: Int, type: Int, buffer: ByteArray?
    ) {
        if (buffer != null) {
            GL33.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, ByteBuffer.wrap(buffer))
        } else {
            // null = read from currently-bound GL_PIXEL_UNPACK_BUFFER at offset 0; LWJGL's
            // ByteBuffer overload isn't @Nullable, so route via the long-offset variant.
            GL33.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, 0L)
        }
    }

    override fun texSubImage2D(
        target: Int, level: Int, xoffset: Int, yoffset: Int, width: Int, height: Int, format: Int, type: Int, offset: Int
    ) = GL33.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, offset.toLong())

    override fun activeTexture(texture: Int) = GL33.glActiveTexture(texture)

    override fun bindTexture(target: Int, texture: KglTexture) = GL33.glBindTexture(target, texture.id)

    override fun generateMipmap(target: Int) = GL33.glGenerateMipmap(target)

    override fun texParameteri(target: Int, pname: Int, value: Int) = GL33.glTexParameteri(target, pname, value)

    override fun drawArrays(mode: Int, first: Int, count: Int) = GL33.glDrawArrays(mode, first, count)

    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) = GL33.glDrawElements(mode, count, type, offset.toLong())

    override fun getError() = GL33.glGetError()

    override fun finish() = GL33.glFinish()

    override fun bindFramebuffer(target: Int, framebuffer: KglFramebuffer) = GL33.glBindFramebuffer(target, framebuffer.id)

    override fun createFramebuffer() = KglFramebuffer(GL33.glGenFramebuffers())

    override fun deleteFramebuffer(framebuffer: KglFramebuffer) = GL33.glDeleteFramebuffers(framebuffer.id)

    override fun checkFramebufferStatus(target: Int) = GL33.glCheckFramebufferStatus(target)

    override fun framebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: KglTexture, level: Int) =
        GL33.glFramebufferTexture2D(target, attachment, textarget, texture.id, level)

    override val supportsTexture3D get() = true
    override fun texImage3D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int, depth: Int,
        border: Int, format: Int, type: Int, buffer: ByteArray?
    ) = GL33.glTexImage3D(
        target, level, internalFormat, width, height, depth, border, format, type,
        buffer?.let { ByteBuffer.wrap(it) }
    )
    override fun texImage3D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int, depth: Int,
        border: Int, format: Int, type: Int, buffer: FloatArray?
    ) = GL33.glTexImage3D(
        target, level, internalFormat, width, height, depth, border, format, type,
        buffer?.let { FloatBuffer.wrap(it) }
    )
    override fun texSubImage3D(
        target: Int, level: Int, xoffset: Int, yoffset: Int, zoffset: Int,
        width: Int, height: Int, depth: Int, format: Int, type: Int, buffer: ByteArray?
    ) {
        if (buffer != null) {
            GL33.glTexSubImage3D(
                target, level, xoffset, yoffset, zoffset, width, height, depth,
                format, type, ByteBuffer.wrap(buffer)
            )
        } else {
            // null = read from currently-bound GL_PIXEL_UNPACK_BUFFER at offset 0; LWJGL's
            // ByteBuffer-typed `glTexSubImage3D` overload isn't @Nullable, so route through
            // the long-offset variant — same idiom used by [texSubImage2D] above.
            GL33.glTexSubImage3D(
                target, level, xoffset, yoffset, zoffset, width, height, depth,
                format, type, 0L
            )
        }
    }
    override fun framebufferTextureLayer(target: Int, attachment: Int, texture: KglTexture, level: Int, layer: Int) =
        GL33.glFramebufferTextureLayer(target, attachment, texture.id, level, layer)

    override val supportsMultisampleFBO get() = true
    override val supportsSizedTextureFormats get() = true
    override fun createRenderbuffer() = KglRenderbuffer(GL33.glGenRenderbuffers())
    override fun deleteRenderbuffer(renderbuffer: KglRenderbuffer) = GL33.glDeleteRenderbuffers(renderbuffer.id)
    override fun bindRenderbuffer(target: Int, renderbuffer: KglRenderbuffer) =
        GL33.glBindRenderbuffer(target, renderbuffer.id)
    override fun renderbufferStorageMultisample(target: Int, samples: Int, internalFormat: Int, width: Int, height: Int) =
        GL33.glRenderbufferStorageMultisample(target, samples, internalFormat, width, height)
    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: KglRenderbuffer) =
        GL33.glFramebufferRenderbuffer(target, attachment, renderbufferTarget, renderbuffer.id)
    override fun blitFramebuffer(
        srcX0: Int, srcY0: Int, srcX1: Int, srcY1: Int,
        dstX0: Int, dstY0: Int, dstX1: Int, dstY1: Int,
        mask: Int, filter: Int
    ) = GL33.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter)

    override fun readPixels(
        x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, buffer: ByteArray
    ) = GL33.glReadPixels(x, y, width, height, format, type, ByteBuffer.wrap(buffer))

    override fun readPixelsToBuffer(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, offset: Int) =
        GL33.glReadPixels(x, y, width, height, format, type, offset.toLong())

    override fun getBufferSubData(target: Int, srcOffset: Int, dst: ByteArray) =
        GL33.glGetBufferSubData(target, srcOffset.toLong(), ByteBuffer.wrap(dst))

    override fun fenceSync() = KglSync(
        org.lwjgl.opengl.GL32.glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
    )

    override fun isSyncSignalled(sync: KglSync): Boolean {
        if (!sync.isValid()) return false
        val result = org.lwjgl.opengl.GL32.glClientWaitSync(sync.id, 0, 0)
        return result == GL_ALREADY_SIGNALED || result == GL_CONDITION_SATISFIED
    }

    override fun deleteSync(sync: KglSync) {
        if (sync.isValid()) org.lwjgl.opengl.GL32.glDeleteSync(sync.id)
    }

    override fun pixelStorei(pname: Int, param: Int) = GL33.glPixelStorei(pname, param)
}
