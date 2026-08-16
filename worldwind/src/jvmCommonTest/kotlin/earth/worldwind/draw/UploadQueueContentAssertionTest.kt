package earth.worldwind.draw

import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.NumericArray
import earth.worldwind.util.kgl.*
import kotlin.test.*

/**
 * Pins the per-frame content-assertion contract (the WorldWind-Java fill-before-draw pattern
 * adapted to the render/draw split): every frame asserts the exact content its draw counts were
 * built against, identity-compared, so a frame drawn after another window replaced the shared
 * buffer's content re-uploads its own content first — and an unchanged, already-resident content
 * costs nothing. Versioned entries keep their monotonic behavior.
 */
class UploadQueueContentAssertionTest {

    private class FakeKgl : Kgl {
        var nextId = 1
        var uploads = 0
        val deleted = mutableListOf<Int>()
        override fun createBuffer(): KglBuffer = KglBuffer(nextId++)
        override fun deleteBuffer(buffer: KglBuffer) { deleted.add(buffer.id) }
        override fun bindBuffer(target: Int, bufferId: KglBuffer) {}
        override fun bufferData(target: Int, size: Int, sourceData: FloatArray?, usage: Int, offset: Int) { uploads++ }
    override val hasMaliOOMBug: Boolean get() = false
    override val glslDerivativesPrefix: String get() = ""
    override fun createShader(type: Int): KglShader = KglShader.NONE
    override fun shaderSource(shader: KglShader, source: String) {}
    override fun compileShader(shader: KglShader) {}
    override fun deleteShader(shader: KglShader) {}
    override fun getShaderParameteri(shader: KglShader, pname: Int): Int = 0
    override fun getProgramInfoLog(program: KglProgram): String = ""
    override fun getShaderInfoLog(shader: KglShader): String = ""
    override fun createProgram(): KglProgram = KglProgram.NONE
    override fun attachShader(program: KglProgram, shader: KglShader) {}
    override fun linkProgram(program: KglProgram) {}
    override fun useProgram(program: KglProgram) {}
    override fun deleteProgram(program: KglProgram) {}
    override fun getProgramParameteri(program: KglProgram, pname: Int): Int = 0
    override fun getUniformLocation(program: KglProgram, name: String): KglUniformLocation = KglUniformLocation.NONE
    override fun bindAttribLocation(program: KglProgram, index: Int, name: String) {}
    override fun enable(cap: Int) {}
    override fun disable(cap: Int) {}
    override fun enableVertexAttribArray(location: Int) {}
    override fun disableVertexAttribArray(location: Int) {}
    override fun getParameteri(pname: Int): Int = 0
    override fun getParameterf(pname: Int): Float = 0f
    override fun getParameteriv(pname: Int): IntArray = IntArray(0)
    override fun getParameterfv(pname: Int): FloatArray = FloatArray(0)
    override fun bufferData(target: Int, size: Int, sourceData: ShortArray?, usage: Int, offset: Int) {}
    override fun bufferData(target: Int, size: Int, sourceData: IntArray?, usage: Int, offset: Int) {}
    override fun bufferData(target: Int, size: Int, sourceData: ByteArray?, usage: Int, offset: Int) {}
    override fun bufferData(target: Int, size: Int, usage: Int) {}
    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: ShortArray) {}
    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: IntArray) {}
    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: FloatArray) {}
    override fun bufferSubData(target: Int, offset: Int, size: Int, sourceData: ByteArray) {}
    override fun mapAndCopyBufferRange(target: Int, offset: Int, length: Int, source: ByteArray, srcOffset: Int, access: Int) {}
    override fun vertexAttribPointer(location: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) {}
    override fun uniform1f(location: KglUniformLocation, f: Float) {}
    override fun uniform1fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) {}
    override fun uniform1i(location: KglUniformLocation, i: Int) {}
    override fun uniform2f(location: KglUniformLocation, x: Float, y: Float) {}
    override fun uniform2fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) {}
    override fun uniform2i(location: KglUniformLocation, x: Int, y: Int) {}
    override fun uniform3f(location: KglUniformLocation, x: Float, y: Float, z: Float) {}
    override fun uniform3fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) {}
    override fun uniform3i(location: KglUniformLocation, x: Int, y: Int, z: Int) {}
    override fun uniform4f(location: KglUniformLocation, x: Float, y: Float, z: Float, w: Float) {}
    override fun uniform4fv(location: KglUniformLocation, count: Int, value: FloatArray, offset: Int) {}
    override fun uniform4i(location: KglUniformLocation, x: Int, y: Int, z: Int, w: Int) {}
    override fun uniformMatrix3fv(location: KglUniformLocation, count: Int, transpose: Boolean, value: FloatArray, offset: Int) {}
    override fun uniformMatrix4fv(location: KglUniformLocation, count: Int, transpose: Boolean, value: FloatArray, offset: Int) {}
    override fun cullFace(mode: Int) {}
    override fun frontFace(mode: Int) {}
    override fun polygonOffset(factor: Float, units: Float) {}
    override fun depthFunc(func: Int) {}
    override fun depthMask(mask: Boolean) {}
    override fun stencilFunc(func: Int, ref: Int, mask: Int) {}
    override fun stencilOp(fail: Int, zFail: Int, zPass: Int) {}
    override fun stencilMask(mask: Int) {}
    override fun clearStencil(s: Int) {}
    override fun blendFunc(sFactor: Int, dFactor: Int) {}
    override fun viewport(x: Int, y: Int, width: Int, height: Int) {}
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) {}
    override fun clear(mask: Int) {}
    override fun createTexture(): KglTexture = KglTexture.NONE
    override fun deleteTexture(texture: KglTexture) {}
    override fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, buffer: ByteArray?) {}
    override fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, buffer: FloatArray?) {}
    override fun texSubImage2D(target: Int, level: Int, xoffset: Int, yoffset: Int, width: Int, height: Int, format: Int, type: Int, buffer: ByteArray?) {}
    override fun texSubImage2D(target: Int, level: Int, xoffset: Int, yoffset: Int, width: Int, height: Int, format: Int, type: Int, offset: Int) {}
    override fun activeTexture(texture: Int) {}
    override fun bindTexture(target: Int, texture: KglTexture) {}
    override fun generateMipmap(target: Int) {}
    override fun texParameteri(target: Int, pname: Int, value: Int) {}
    override fun drawArrays(mode: Int, first: Int, count: Int) {}
    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) {}
    override fun getError(): Int = 0
    override fun finish() {}
    override fun bindFramebuffer(target: Int, framebuffer: KglFramebuffer) {}
    override fun createFramebuffer(): KglFramebuffer = KglFramebuffer.NONE
    override fun deleteFramebuffer(framebuffer: KglFramebuffer) {}
    override fun checkFramebufferStatus(target: Int): Int = 0
    override fun framebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: KglTexture, level: Int) {}
    override val supportsMultisampleFBO: Boolean get() = false
    override val supportsSizedTextureFormats: Boolean get() = false
    override fun createRenderbuffer(): KglRenderbuffer = KglRenderbuffer.NONE
    override fun deleteRenderbuffer(renderbuffer: KglRenderbuffer) {}
    override fun bindRenderbuffer(target: Int, renderbuffer: KglRenderbuffer) {}
    override fun renderbufferStorageMultisample(target: Int, samples: Int, internalFormat: Int, width: Int, height: Int) {}
    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: KglRenderbuffer) {}
    override fun blitFramebuffer( srcX0: Int, srcY0: Int, srcX1: Int, srcY1: Int, dstX0: Int, dstY0: Int, dstX1: Int, dstY1: Int, mask: Int, filter: Int ) {}
    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, buffer: ByteArray) {}
    override fun readPixelsToBuffer(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, offset: Int) {}
    override fun getBufferSubData(target: Int, srcOffset: Int, dst: ByteArray) {}
    override fun fenceSync(): KglSync = KglSync.NONE
    override fun isSyncSignalled(sync: KglSync): Boolean = false
    override fun deleteSync(sync: KglSync) {}
    override fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean) {}
    override fun lineWidth(width: Float) {}
    override fun pixelStorei(pname: Int, param: Int) {}
    override fun drawBuffers(bufs: IntArray) {}
    override fun readBuffer(src: Int) {}
    }

    private lateinit var kgl: FakeKgl
    private lateinit var dc: DrawContext
    private lateinit var buffer: BufferObject
    private val contentA = NumericArray.Floats(floatArrayOf(1f))
    private val contentB = NumericArray.Floats(floatArrayOf(2f))

    @BeforeTest
    fun setUp() {
        kgl = FakeKgl()
        dc = DrawContext(kgl)
        buffer = BufferObject(GL_ARRAY_BUFFER, 0)
    }

    private fun frameAsserting(array: NumericArray) = UploadQueue().also { it.queueContentAssertion(buffer, array) }

    @Test
    fun unchangedContentUploadsOnce() {
        frameAsserting(contentA).processUploads(dc)
        frameAsserting(contentA).processUploads(dc) // next frame, same content instance

        assertEquals(1, kgl.uploads, "identical content is asserted for free")
        assertSame(contentA, buffer.contentArray)
    }

    @Test
    fun laggingFrameReassertsItsOlderContent() {
        val lagging = frameAsserting(contentA) // rendered against A, queued but not yet drawn
        frameAsserting(contentB).processUploads(dc) // another window's frame draws first
        assertSame(contentB, buffer.contentArray)

        lagging.processUploads(dc) // the lagging frame draws: must see A again

        assertSame(contentA, buffer.contentArray, "lagging frame re-uploads the content its counts were built against")
        assertEquals(2, kgl.uploads)
    }

    @Test
    fun redrawOfSameFrameReassertsAgainstInterleavedUploads() {
        val frame = frameAsserting(contentA)
        frame.processUploads(dc) // first draw
        frameAsserting(contentB).processUploads(dc) // other window draws in between
        frame.processUploads(dc) // same frame drawn again (GL thread outpacing render)

        assertSame(contentA, buffer.contentArray, "entries persist so a redraw re-asserts")
        assertEquals(3, kgl.uploads)
    }

    @Test
    fun deletedBufferIsReloadedByNextAssertion() {
        frameAsserting(contentA).processUploads(dc)
        buffer.release(dc)
        assertNull(buffer.contentArray, "released buffer holds nothing")

        frameAsserting(contentA).processUploads(dc)

        assertEquals(2, kgl.uploads, "assertion restores content after release")
        assertSame(contentA, buffer.contentArray)
    }

    @Test
    fun versionedEntriesKeepMonotonicBehavior() {
        var fired = 0
        val queue = UploadQueue()
        queue.queueBufferUpload(buffer, contentA, 1) { fired++ }
        queue.queueBufferUpload(buffer, contentB, 1) // same version: skipped
        queue.processUploads(dc)
        queue.processUploads(dc) // redraw: no re-upload, no callback re-fire

        assertEquals(1, kgl.uploads)
        assertEquals(1, buffer.version)
        assertEquals(1, fired, "onUploaded fires exactly once per entry")
    }

    @Test
    fun clearUploadsRecyclesEntries() {
        val queue = UploadQueue()
        queue.queueContentAssertion(buffer, contentA)
        queue.clearUploads()
        queue.processUploads(dc)

        assertEquals(0, kgl.uploads, "cleared entries assert nothing")
        assertEquals(0, queue.count)
    }
}
