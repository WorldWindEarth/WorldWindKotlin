package earth.worldwind.render.video

import earth.worldwind.util.kgl.GL_BGRA
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer

/**
 * JVM video texture driven by VLCJ (libVLC bindings). VLCJ's `CallbackVideoSurface`
 * decodes frames in BGRA (RV32) into a native `ByteBuffer`; on every render callback we
 * copy them into the [CallbackVideoTexture] base and the GL thread uploads on bind.
 *
 * **Runtime requirement:** VLC 3.0+ installed and discoverable by VLCJ (system-wide
 * install is simplest; VLCJ also accepts `-Djna.library.path=…`). Without VLC,
 * [MediaPlayerFactory] construction fails — the tutorial wraps that in try/catch.
 */
class VlcjVideoTexture(
    /** URL or filesystem path to the media (e.g. `"C:/.../drone.mp4"`). */
    val mediaUrl: String,
    width: Int, height: Int,
) : CallbackVideoTexture(width, height, uploadFormat = GL_BGRA), VideoPlayback {

    /**
     * `--avcodec-hw=any` lets libVLC pick a hardware decoder for the source codec when one
     * is available (DXVA2/D3D11VA on Windows, VAAPI on Linux, VideoToolbox on macOS) and
     * falls back to software decode silently when not. Drops decode CPU on a single 1080p
     * H.264 stream from ~5% to <1% on a typical laptop, and matters more as the number of
     * concurrent streams climbs.
     */
    private val factory = MediaPlayerFactory("--avcodec-hw=any")
    private val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

    init {
        // RV32 = 32-bit BGRA on little-endian — matches our uploadFormat. `useDirectBuffers`
        // (3rd arg) is mandatory in VLCJ 4.x and lets the base copy straight off the
        // decoder's mapped memory via the ByteBuffer submitFrame overload (no scratch hop).
        val surface = factory.videoSurfaces().newVideoSurface(
            object : BufferFormatCallback {
                override fun getBufferFormat(w: Int, h: Int): BufferFormat = RV32BufferFormat(w, h)
                override fun allocatedBuffers(buffers: Array<out ByteBuffer>) { /* no-op */ }
            },
            RenderCallback { _, buffers, fmt ->
                buffers[0].rewind()
                submitFrame(buffers[0], fmt.width, fmt.height)
            },
            true,
        )
        player.videoSurface().set(surface)
    }

    /**
     * Start (or resume) playback. Re-entrant: the first call prepares the media and starts
     * playing; subsequent calls just resume after [pause].
     */
    override fun play() {
        if (player.status().length() <= 0) player.media().play(mediaUrl)
        else player.controls().play()
    }
    override fun pause() = player.controls().pause()
    override fun stop() = player.controls().stop()

    /** True once libVLC has parsed the media and is decoding. */
    val isPlaying: Boolean get() = player.status().isPlaying

    /** Current playback time in milliseconds, or `-1` when no media is loaded. */
    override val timeMs: Long get() = player.status().time()

    override fun release() {
        player.release()
        factory.release()
    }
}
