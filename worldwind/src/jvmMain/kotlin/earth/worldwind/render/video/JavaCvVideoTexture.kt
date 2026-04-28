package earth.worldwind.render.video

import earth.worldwind.util.Logger
import earth.worldwind.util.kgl.GL_BGRA
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM video texture driven by JavaCV's high-level [FFmpegFrameGrabber]. A worker thread
 * loops on `grabImage()`, copies the BGRA frame bytes out, and pushes them to the
 * [CallbackVideoTexture] base for GL-thread upload.
 *
 * Compared to the raw FFmpeg path ([FFmpegVideoTexture]), this is far shorter — the
 * grabber handles demuxing, decoding, and pixel-format conversion. Tradeoff: less
 * control over codec selection, hardware acceleration, and the exact pixel format.
 *
 * **Runtime requirement:** FFmpeg natives via `org.bytedeco:javacv-platform`.
 *
 * Pacing: the loop sleeps to roughly match the source frame rate. Without it, decode
 * runs at I/O speed and most frames are dropped at the GL upload (which only refreshes
 * once per render tick).
 */
class JavaCvVideoTexture(
    /** Filesystem path or URL to the media. */
    val mediaUrl: String,
    width: Int, height: Int,
) : CallbackVideoTexture(width, height, uploadFormat = GL_BGRA), VideoPlayback {

    private val grabber = FFmpegFrameGrabber(mediaUrl).apply { pixelFormat = avutil.AV_PIX_FMT_BGRA }
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var positionMs = 0L

    override fun play() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::runDecodeLoop, "javacv-decode-${hashCode()}").apply {
            isDaemon = true; start()
        }
    }
    override fun pause() { running.set(false) }
    override fun stop() { running.set(false); thread?.join(500) }
    override val timeMs: Long get() = positionMs
    override fun release() {
        stop()
        runCatching { grabber.release() }
    }

    private fun runDecodeLoop() {
        try {
            grabber.start()
            val frameInterval = (1000.0 / grabber.frameRate.coerceAtLeast(1.0)).toLong().coerceAtLeast(1L)
            // The base [submitFrame(ByteBuffer)] reads the buffer's remaining bytes and copies
            // straight into the texture's heap pixel store — no scratch ByteArray needed.
            // Resolution can change mid-stream (rare for files, common for live streams) — we
            // re-read `imageWidth/imageHeight` per frame.
            var nextDeadline = System.currentTimeMillis()
            while (running.get()) {
                val frame = runCatching { grabber.grabImage() }.getOrNull()
                if (frame == null) {
                    // EOF — rewind and continue. The KLV ticker re-anchors via positionMs.
                    grabber.timestamp = 0L
                    nextDeadline = System.currentTimeMillis()
                    continue
                }
                val bb = frame.image?.getOrNull(0) as? ByteBuffer ?: continue
                val w = grabber.imageWidth
                val h = grabber.imageHeight
                bb.position(0).limit(minOf(bb.capacity(), w * h * 4))
                submitFrame(bb, w, h)
                positionMs = (grabber.timestamp / 1000L).coerceAtLeast(0L)
                nextDeadline += frameInterval
                val sleep = nextDeadline - System.currentTimeMillis()
                if (sleep > 0) Thread.sleep(sleep)
            }
        } catch (_: InterruptedException) { /* graceful */
        } catch (e: Throwable) {
            Logger.log(Logger.WARN, "JavaCvVideoTexture: decode loop terminated: ${e.message}")
        }
    }
}
