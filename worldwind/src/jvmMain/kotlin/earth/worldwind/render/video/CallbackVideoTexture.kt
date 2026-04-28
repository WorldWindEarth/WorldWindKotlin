package earth.worldwind.render.video

import earth.worldwind.WorldWind
import earth.worldwind.draw.DrawContext
import earth.worldwind.render.Texture
import earth.worldwind.util.kgl.GL_MAP_INVALIDATE_BUFFER_BIT
import earth.worldwind.util.kgl.GL_MAP_UNSYNCHRONIZED_BIT
import earth.worldwind.util.kgl.GL_MAP_WRITE_BIT
import earth.worldwind.util.kgl.GL_ONE
import earth.worldwind.util.kgl.GL_PIXEL_UNPACK_BUFFER
import earth.worldwind.util.kgl.GL_RGBA
import earth.worldwind.util.kgl.GL_STREAM_DRAW
import earth.worldwind.util.kgl.GL_TEXTURE_2D
import earth.worldwind.util.kgl.GL_TEXTURE_SWIZZLE_A
import earth.worldwind.util.kgl.GL_UNSIGNED_BYTE
import earth.worldwind.util.kgl.KglBuffer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared base for JVM-side video textures whose source pushes decoded frames from a worker
 * thread (libVLC, FFmpeg, JavaCV, JavaFX `MediaView`-snapshot, …):
 *
 *  * Subclasses call [submitFrame] (ByteArray or [ByteBuffer]) on the decoder thread; the
 *    base atomically publishes a fresh [FrameBuf] to the GL thread — **lock-free**.
 *  * On every [bindTexture] (GL thread), if a new frame is pending, the base atomically
 *    consumes it via `getAndSet(null)` and uploads via `glTexSubImage2D` (or `glTexImage2D`
 *    on first frame / resolution change). With nothing pending the upload is skipped —
 *    the driver keeps the previous frame's storage live.
 *
 * Optimizations vs. a naive blit:
 *  * **`GL_TEXTURE_SWIZZLE_A = GL_ONE`** — sampler returns alpha=1.0 regardless of the
 *    texture bytes; eliminates a per-frame CPU pass to force every 4th byte to `0xFF`.
 *  * **`glTexSubImage2D` for steady-state** — re-uploads pixels into existing GPU storage
 *    instead of re-allocating with `glTexImage2D` every frame.
 *  * **Optional PBO path ([usePbo])** — uploads route through `GL_PIXEL_UNPACK_BUFFER`
 *    via `glMapBufferRange(... INVALIDATE_BUFFER | UNSYNCHRONIZED)` + memcpy +
 *    `glUnmapBuffer`. Driver hands back fresh staging memory directly while the previous
 *    transfer is still in flight; fewer driver round-trips than the
 *    `bufferData(null) + bufferData(data)` orphan idiom.
 *  * **Lock-free frame publish** — no `synchronized` between decoder and GL threads. Two
 *    `AtomicReference<FrameBuf>` slots (`latest` + `recycled`) serve as the producer /
 *    consumer queue; at steady state at most two `ByteArray`s exist in flight.
 *
 * Internal format is `GL_RGBA` (which JoglKgl maps to renderable `GL_RGBA8`); upload
 * pixel-format ([uploadFormat]) is whatever the decoder produces — `GL_BGRA` for libVLC
 * RV32, FFmpeg/JavaCV after `sws_scale`, and JavaFX `PixelFormat.byteBgra`.
 */
abstract class CallbackVideoTexture(
    width: Int, height: Int,
    protected val uploadFormat: Int,
    /**
     * Route uploads via a [GL_PIXEL_UNPACK_BUFFER]. Default off — the synchronous
     * `texSubImage2D` path is fine for typical 720p / 1080p video and is one fewer
     * object to manage. Requires GLES3+ / WebGL2 / GL3+.
     */
    private val usePbo: Boolean = false,
) : Texture(
    width, height,
    format = uploadFormat, type = GL_UNSIGNED_BYTE, isRT = false,
    // GL_BGRA isn't a renderable internal format on most drivers — pick a sized one.
    internalFormat = GL_RGBA,
) {
    /** A decoded frame's pixels and dimensions, owned exclusively by one thread at a time. */
    private class FrameBuf(val bytes: ByteArray, val w: Int, val h: Int)

    /** Decoder publishes here via `getAndSet(frame)`; GL thread consumes via `getAndSet(null)`. */
    private val latest = AtomicReference<FrameBuf?>()
    /** GL thread parks the consumed buffer here; decoder reuses it via `getAndSet(null)`. */
    private val recycled = AtomicReference<FrameBuf?>()

    /** Storage size last (re-)allocated on the texture; texSubImage2D is safe only when w/h match. */
    private var allocatedW = 0
    private var allocatedH = 0
    /** PBO handle and its currently-allocated capacity. */
    private var pbo: KglBuffer? = null
    private var pboCapacity = 0

    init {
        // Decoders write rows top-first; GL samples bottom-up. Same convention as ImageTexture.
        coordTransform.setToVerticalFlip()
    }

    /**
     * Decoder-thread entry — ByteArray flavor. [bytes] holds at least `w * h * 4` pixels in
     * [uploadFormat] order; the buffer is copied (the decoder is free to reuse it).
     */
    protected fun submitFrame(bytes: ByteArray, w: Int, h: Int) =
        publishFrame(w, h, bytes.size) { dst, n -> System.arraycopy(bytes, 0, dst, 0, n) }

    /**
     * Decoder-thread entry — [ByteBuffer] flavor. Avoids a scratch-array hop for sources
     * that already produce a NIO buffer (libVLC's native callback, FFmpeg's
     * `BytePointer`-backed scaled frame, …). Reads from the buffer's current position.
     */
    protected fun submitFrame(bytes: ByteBuffer, w: Int, h: Int) =
        publishFrame(w, h, bytes.remaining()) { dst, n -> bytes.duplicate().get(dst, 0, n) }

    /**
     * Common publication step: pull a recyclable buffer (or alloc fresh), copy via
     * [copyInto], atomically swap into [latest], and return the previously-pending buffer
     * (if any) to the [recycled] slot for the next decode tick.
     *
     * No locks. Memory visibility is guaranteed by the AtomicReference's volatile-semantics
     * `getAndSet`: writes to the byte array on the decoder thread happen-before the
     * publish, and the GL thread's reads on take happen-after.
     */
    private inline fun publishFrame(w: Int, h: Int, available: Int, copyInto: (dst: ByteArray, n: Int) -> Unit) {
        val byteCount = w * h * 4
        require(available >= byteCount) { "submitFrame: $available bytes available, need $byteCount for ${w}x$h" }

        // Take a recycled buffer if it matches dimensions; else alloc fresh.
        val buf = recycled.getAndSet(null)?.takeIf { it.bytes.size == byteCount }?.bytes
            ?: ByteArray(byteCount)
        copyInto(buf, byteCount)

        // Atomic publish. The previous `latest` (if GL hadn't consumed it yet) goes back
        // into the recycle slot — at most two buffers are ever in flight.
        latest.getAndSet(FrameBuf(buf, w, h))?.let(::parkForRecycle)

        // Trigger a redraw so the GL thread runs and bindTexture uploads this frame.
        WorldWind.requestRedraw()
    }

    /**
     * Allocate a 1×1 black-but-opaque placeholder. The real storage is (re-)allocated
     * lazily in [bindTexture] when the first frame arrives — avoids a wasted full-size
     * allocation here, then a re-spec a few ms later when the decoder reports its real
     * frame size.
     *
     * Also configures `GL_TEXTURE_SWIZZLE_A = GL_ONE` so the sampler returns a=1.0 — no
     * per-frame alpha-force needed. Available since GLES3 / GL3.3 / WebGL2; older
     * runtimes silently ignore the call (and our decoders emit 0xFF after sws_scale anyway).
     */
    override fun allocTexImage(dc: DrawContext) {
        dc.gl.texImage2D(GL_TEXTURE_2D, 0, internalFormat, 1, 1, 0, format, type, ByteArray(4))
        dc.gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_A, GL_ONE)
        allocatedW = 1
        allocatedH = 1
    }

    override fun bindTexture(dc: DrawContext): Boolean {
        if (!super.bindTexture(dc)) return false
        // Atomic consume — empty if no new frame, no blocking either way.
        val frame = latest.getAndSet(null) ?: return true
        uploadFrame(dc, frame.bytes, frame.w, frame.h)
        parkForRecycle(frame)
        return true
    }

    /**
     * Park [frame] in the single-slot [recycled] queue for the decoder to pick up. If the
     * slot is already occupied (a faster-than-GL decoder published two frames before GL
     * consumed the first), the new candidate drops to GC — keeping the queue at most one
     * buffer keeps the working set small (≤2 in flight) and avoids unbounded growth.
     */
    private fun parkForRecycle(frame: FrameBuf) {
        recycled.compareAndSet(null, frame)
    }

    /**
     * Upload [pixels] to the bound 2D texture. On the first frame and on any resolution
     * change we use [texImage2D] to (re-)allocate GPU storage; subsequent same-size frames
     * use [texSubImage2D] (cheaper, no realloc) — optionally routed via a PBO when
     * [usePbo] is true.
     */
    private fun uploadFrame(dc: DrawContext, pixels: ByteArray, w: Int, h: Int) {
        if (w != allocatedW || h != allocatedH) {
            // Respec via direct texImage2D — even in PBO mode, the spec call doesn't
            // benefit from the PBO and a `texImage2D(... offset)` overload isn't exposed
            // uniformly across platforms. Re-create storage and skip PBO this frame.
            dc.gl.texImage2D(GL_TEXTURE_2D, 0, internalFormat, w, h, 0, uploadFormat, GL_UNSIGNED_BYTE, pixels)
            allocatedW = w
            allocatedH = h
            return
        }
        if (usePbo) uploadViaPbo(dc, pixels, w, h)
        else dc.gl.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, uploadFormat, GL_UNSIGNED_BYTE, pixels)
    }

    /**
     * Stage [pixels] into a `GL_PIXEL_UNPACK_BUFFER` via `glMapBufferRange` with the
     * standard "rename" access bits, then `texSubImage2D` from PBO offset 0. The
     * `INVALIDATE_BUFFER_BIT | UNSYNCHRONIZED_BIT` combination tells the driver to give us
     * fresh storage without waiting for any in-flight transfer of the previous contents —
     * one fewer GPU sync per frame than the `bufferData(null) + bufferData(data)` idiom.
     *
     * The PBO is allocated once at the matching capacity and reused thereafter; a
     * resolution change reallocates lazily via the [allocatedW]/[allocatedH] respec path
     * (which doesn't go through PBO either way).
     */
    private fun uploadViaPbo(dc: DrawContext, pixels: ByteArray, w: Int, h: Int) {
        val byteCount = w * h * 4
        val handle = pbo ?: dc.gl.createBuffer().also { pbo = it }
        dc.gl.bindBuffer(GL_PIXEL_UNPACK_BUFFER, handle)
        if (pboCapacity != byteCount) {
            dc.gl.bufferData(GL_PIXEL_UNPACK_BUFFER, byteCount, null as ByteArray?, GL_STREAM_DRAW)
            pboCapacity = byteCount
        }
        dc.gl.mapAndCopyBufferRange(
            GL_PIXEL_UNPACK_BUFFER, 0, byteCount, pixels, 0,
            access = GL_MAP_WRITE_BIT or GL_MAP_INVALIDATE_BUFFER_BIT or GL_MAP_UNSYNCHRONIZED_BIT
        )
        dc.gl.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, uploadFormat, GL_UNSIGNED_BYTE, 0)
        dc.gl.bindBuffer(GL_PIXEL_UNPACK_BUFFER, KglBuffer.NONE)
    }

    override fun deleteTexture(dc: DrawContext) {
        pbo?.let { dc.gl.deleteBuffer(it) }
        pbo = null
        pboCapacity = 0
        latest.set(null)
        recycled.set(null)
        super.deleteTexture(dc)
    }
}
