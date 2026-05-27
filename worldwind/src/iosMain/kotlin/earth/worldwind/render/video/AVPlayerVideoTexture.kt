@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package earth.worldwind.render.video

import earth.worldwind.draw.DrawContext
import earth.worldwind.render.Texture
import earth.worldwind.util.kgl.GL_BGRA
import earth.worldwind.util.kgl.GL_RGBA
import earth.worldwind.util.kgl.GL_TEXTURE_2D
import earth.worldwind.util.kgl.GL_UNPACK_ALIGNMENT
import earth.worldwind.util.kgl.GL_UNSIGNED_BYTE
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.addOutput
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatBGRA8
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferIOSurfacePropertiesKey
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSURL
import platform.posix.memcpy

/**
 * iOS [Texture] that pipes [AVPlayer] frames into a `GL_TEXTURE_2D`. Real iOS devices honour
 * our 32BGRA + IOSurface request and the buffer uploads directly via the packed path. Hosts
 * that ignore the request (notably Mac "Designed for iPad" Simulator, which returns a Lossy
 * GPU-resident planar buffer) take the CoreImage path: a GPU readback into a CPU BGRA8
 * bitmap that uploads through the same `texSubImage2D` call.
 */
class AVPlayerVideoTexture(
    val player: AVPlayer,
    width: Int,
    height: Int,
) : Texture(width, height, format = GL_BGRA, type = GL_UNSIGNED_BYTE, isRT = false, internalFormat = GL_RGBA) {

    private val output = AVPlayerItemVideoOutput(
        pixelBufferAttributes = mapOf<Any?, Any>(
            kCVPixelBufferPixelFormatTypeKey to kCVPixelFormatType_32BGRA,
            kCVPixelBufferIOSurfacePropertiesKey to mapOf<Any?, Any>(),
        )
    )

    private var allocated = false
    /** Currently allocated GL storage dims; constructor (width, height) are placeholders -
     *  the first frame's [respec] re-creates storage at the actual decoded size. */
    private var allocatedW = 0
    private var allocatedH = 0
    /** Reusable upload-staging buffer, grows monotonically. */
    private var stagingBytes = ByteArray(0)
    /** Lazily-built CoreImage context; only the GPU-readback path uses it. */
    private var ciContext: CIContext? = null
    private val ciColorSpace by lazy { CGColorSpaceCreateDeviceRGB() }

    init {
        player.currentItem?.addOutput(output)
        // Pixel buffer rows arrive top-down (CG convention); GL samples bottom-up.
        coordTransform.setToVerticalFlip()
    }

    /** Convenience accessor for `VideoOnTerrainTutorial`'s `currentTimeMs` callback. */
    fun currentTimeMs(): Long = (CMTimeGetSeconds(player.currentTime()) * 1000.0).toLong().coerceAtLeast(0L)

    fun play() = player.play()
    fun pause() = player.pause()

    override fun allocTexImage(dc: DrawContext) {
        if (allocated) return
        // 1x1 placeholder; [respec] re-creates real storage at the first frame's actual size.
        dc.gl.pixelStorei(GL_UNPACK_ALIGNMENT, 1)
        dc.gl.texImage2D(GL_TEXTURE_2D, 0, internalFormat, 1, 1, 0, format, type, ByteArray(4))
        dc.gl.pixelStorei(GL_UNPACK_ALIGNMENT, 4)
        allocated = true
        allocatedW = 1
        allocatedH = 1
    }

    override fun bindTexture(dc: DrawContext): Boolean {
        if (!super.bindTexture(dc)) return false
        // Skip frame queries until the item is ready - querying earlier emits AVFoundation
        // "(Fig) signalled err=-12900" warnings every tick.
        val item = player.currentItem ?: return true
        if (item.status != AVPlayerItemStatusReadyToPlay) return true
        val time = player.currentTime()
        if (!output.hasNewPixelBufferForItemTime(itemTime = time)) return true
        val pixelBuffer = output.copyPixelBufferForItemTime(itemTime = time, itemTimeForDisplay = null) ?: return true
        try { uploadPixelBuffer(dc, pixelBuffer) } finally { CFRelease(pixelBuffer) }
        return true
    }

    private fun uploadPixelBuffer(dc: DrawContext, buf: CVPixelBufferRef) {
        // Real iOS device delivers packed 32BGRA - direct memcpy upload. Anything else
        // (planar YUV, Lossy GPU surfaces, anything Apple decided to give us instead of BGRA)
        // routes through CoreImage which renders to a BGRA bitmap on the GPU.
        if (CVPixelBufferGetPixelFormatType(buf) != kCVPixelFormatType_32BGRA) {
            uploadViaCoreImage(dc, buf)
            return
        }
        CVPixelBufferLockBaseAddress(buf, 1u)  // kCVPixelBufferLock_ReadOnly
        try {
            val w = CVPixelBufferGetWidth(buf).toInt()
            val h = CVPixelBufferGetHeight(buf).toInt()
            val bpr = CVPixelBufferGetBytesPerRow(buf).toInt()
            val base = CVPixelBufferGetBaseAddress(buf)?.reinterpret<ByteVar>() ?: return
            uploadPackedBgra(dc, base, w, h, bpr)
        } finally {
            CVPixelBufferUnlockBaseAddress(buf, 1u)
        }
    }

    private fun uploadPackedBgra(dc: DrawContext, src: CPointer<ByteVar>, w: Int, h: Int, bpr: Int) {
        val rowBytes = w * 4
        val bytes = staging(rowBytes * h)
        if (bpr == rowBytes) {
            bytes.usePinned { p -> memcpy(p.addressOf(0), src, (rowBytes * h).toULong()) }
        } else {
            // Decoder padded the rows; copy each row's content bytes only.
            bytes.usePinned { p ->
                val dst = p.addressOf(0).toLong()
                val s = src.toLong()
                for (y in 0 until h) memcpy(
                    (dst + y * rowBytes).toCPointer<ByteVar>(),
                    (s + y * bpr).toCPointer<ByteVar>(),
                    rowBytes.toULong(),
                )
            }
        }
        respec(dc, w, h)
        dc.gl.pixelStorei(GL_UNPACK_ALIGNMENT, 1)
        dc.gl.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, format, type, bytes)
        dc.gl.pixelStorei(GL_UNPACK_ALIGNMENT, 4)
    }

    private fun uploadViaCoreImage(dc: DrawContext, buf: CVPixelBufferRef) {
        val w = CVPixelBufferGetWidth(buf).toInt()
        val h = CVPixelBufferGetHeight(buf).toInt()
        val ctx = ciContext ?: CIContext().also { ciContext = it }
        val out = staging(w * h * 4)
        out.usePinned { p ->
            ctx.render(
                image = CIImage(cVPixelBuffer = buf),
                toBitmap = p.addressOf(0),
                rowBytes = (w * 4).toLong(),
                bounds = CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()),
                format = kCIFormatBGRA8,
                colorSpace = ciColorSpace,
            )
        }
        respec(dc, w, h)
        dc.gl.pixelStorei(GL_UNPACK_ALIGNMENT, 1)
        dc.gl.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, format, type, out)
        dc.gl.pixelStorei(GL_UNPACK_ALIGNMENT, 4)
    }

    /** Re-allocate GL storage when the source dims don't match what's currently bound. */
    private fun respec(dc: DrawContext, w: Int, h: Int) {
        if (w == allocatedW && h == allocatedH) return
        dc.gl.pixelStorei(GL_UNPACK_ALIGNMENT, 1)
        dc.gl.texImage2D(GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, null as ByteArray?)
        dc.gl.pixelStorei(GL_UNPACK_ALIGNMENT, 4)
        allocatedW = w
        allocatedH = h
    }

    private fun staging(size: Int): ByteArray {
        if (stagingBytes.size < size) stagingBytes = ByteArray(size)
        return stagingBytes
    }

    companion object {
        /** Build an AVPlayer + texture pair from a file URL or remote streaming URL. */
        fun create(url: NSURL, width: Int, height: Int): AVPlayerVideoTexture {
            val item = AVPlayerItem(uRL = url)
            val player = AVPlayer(playerItem = item)
            return AVPlayerVideoTexture(player, width, height)
        }
    }
}
