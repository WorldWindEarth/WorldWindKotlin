package earth.worldwind.render.video

import earth.worldwind.util.Logger
import earth.worldwind.util.kgl.GL_BGRA
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVBufferRef
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import java.nio.DoubleBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM video texture driven by *direct* javacpp-presets FFmpeg — no JavaCV wrapper. A worker
 * thread runs the demux→decode→scale loop by hand:
 *
 *  1. `av_read_frame` → [AVPacket]
 *  2. `avcodec_send_packet` / `avcodec_receive_frame` → [AVFrame] in source pixel format
 *  3. (optional) `av_hwframe_transfer_data` → [AVFrame] in CPU-accessible format
 *  4. `sws_scale` → [AVFrame] in packed BGRA
 *  5. Copy BGRA bytes into a heap [ByteArray] and push to the [CallbackVideoTexture] base.
 *
 * Compare with [JavaCvVideoTexture] which does the same in ~50 lines via
 * [org.bytedeco.javacv.FFmpegFrameGrabber]. The raw path exists for pedagogy and as a
 * starting point for hardware acceleration / custom codec selection / frame seeking.
 *
 * **Runtime requirement:** `org.bytedeco:javacv-platform` (or `org.bytedeco:ffmpeg` with
 * the host classifier) on the classpath.
 */
class FFmpegVideoTexture(
    /** Filesystem path or URL to the media. */
    val mediaUrl: String,
    width: Int, height: Int,
    /**
     * Try to enable hardware-accelerated decoding (DXVA2/D3D11VA on Windows, VAAPI on
     * Linux, VideoToolbox on macOS, CUDA where available). Falls back to software decode
     * if no compatible HW config is found for the source codec or if device init fails.
     * Default `true`.
     */
    private val tryHardwareDecode: Boolean = true,
) : CallbackVideoTexture(width, height, uploadFormat = GL_BGRA), VideoPlayback {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var positionMs = 0L
    @Volatile private var hwDecodeActive = false

    /** True after [play] if FFmpeg picked a hardware decoder for the source codec. */
    val hardwareAcceleratedDecode: Boolean get() = hwDecodeActive

    override fun play() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::runDecodeLoop, "ffmpeg-decode-${hashCode()}").apply {
            isDaemon = true; start()
        }
    }
    override fun pause() { running.set(false) }
    override fun stop() { running.set(false); thread?.join(500) }
    override val timeMs: Long get() = positionMs
    override fun release() = stop()

    /** Picked HW config (device-type + the GPU-side pixel format AVFrames will carry). */
    private data class HwConfig(val deviceType: Int, val pixFmt: Int)

    private fun runDecodeLoop() {
        var fmtCtx: AVFormatContext? = null
        var codecCtx: AVCodecContext? = null
        var packet: AVPacket? = null
        var srcFrame: AVFrame? = null
        var swFrame: AVFrame? = null
        var dstFrame: AVFrame? = null
        var swsCtx: SwsContext? = null
        var dstBuffer: BytePointer? = null
        var hwDeviceCtx: AVBufferRef? = null
        var hwPixFmt = avutil.AV_PIX_FMT_NONE

        try {
            fmtCtx = AVFormatContext(null)
            check(avformat.avformat_open_input(fmtCtx, mediaUrl, null, null) == 0) { "open_input failed" }
            check(avformat.avformat_find_stream_info(fmtCtx, null as PointerPointer<*>?) >= 0) { "find_stream_info failed" }

            val streamIdx = avformat.av_find_best_stream(
                fmtCtx, avutil.AVMEDIA_TYPE_VIDEO, -1, -1, null as PointerPointer<*>?, 0
            )
            check(streamIdx >= 0) { "no video stream in $mediaUrl" }
            val stream = fmtCtx.streams(streamIdx)
            val codec = avcodec.avcodec_find_decoder(stream.codecpar().codec_id())
                ?: error("no decoder for codec_id=${stream.codecpar().codec_id()}")
            codecCtx = avcodec.avcodec_alloc_context3(codec)
            check(avcodec.avcodec_parameters_to_context(codecCtx, stream.codecpar()) >= 0) { "params_to_context failed" }

            // Probe & wire HW decode if requested. On success codecCtx.hw_device_ctx is
            // attached and AVFrames come back in `hwPixFmt` (e.g. NV12 in GPU memory),
            // which we then transfer to CPU via av_hwframe_transfer_data per frame.
            if (tryHardwareDecode) {
                hwDeviceCtx = tryEnableHwDecode(codec, codecCtx)?.also { (_, picked) ->
                    hwPixFmt = picked.pixFmt
                    hwDecodeActive = true
                }?.first
            }

            check(avcodec.avcodec_open2(codecCtx, codec, null as PointerPointer<*>?) >= 0) { "open2 failed" }

            val w = codecCtx.width()
            val h = codecCtx.height()
            val byteCount = w * h * 4
            val frameInterval = stream.r_frame_rate().let { r ->
                if (r.den() > 0 && r.num() > 0) (1000L * r.den() / r.num()).coerceAtLeast(1L) else 33L
            }

            packet = avcodec.av_packet_alloc()
            srcFrame = avutil.av_frame_alloc()
            if (hwDecodeActive) swFrame = avutil.av_frame_alloc()
            dstFrame = avutil.av_frame_alloc()
            dstBuffer = BytePointer(byteCount.toLong())
            avutil.av_image_fill_arrays(
                dstFrame.data(), dstFrame.linesize(), dstBuffer, avutil.AV_PIX_FMT_BGRA, w, h, 1
            )
            // sws source format is determined per-frame after a possible HW→SW transfer
            // (the SW frame's `format()` is the chroma-subsampled YUV the GPU decoder
            // delivered, e.g. NV12). Build the scaler lazily on first frame.
            var swsSrcFmt = avutil.AV_PIX_FMT_NONE

            val scratch = ByteArray(byteCount)
            var nextDeadline = System.currentTimeMillis()

            while (running.get()) {
                if (avformat.av_read_frame(fmtCtx, packet) < 0) {
                    // EOF — rewind and keep looping.
                    avformat.av_seek_frame(fmtCtx, streamIdx, 0L, avformat.AVSEEK_FLAG_BACKWARD)
                    avcodec.avcodec_flush_buffers(codecCtx)
                    nextDeadline = System.currentTimeMillis()
                    continue
                }
                if (packet.stream_index() != streamIdx) {
                    avcodec.av_packet_unref(packet)
                    continue
                }
                avcodec.avcodec_send_packet(codecCtx, packet)
                avcodec.av_packet_unref(packet)

                while (running.get()) {
                    val ret = avcodec.avcodec_receive_frame(codecCtx, srcFrame)
                    if (ret == avutil.AVERROR_EAGAIN() || ret == avutil.AVERROR_EOF || ret < 0) break

                    // Defensive: skip on mid-stream resolution change (sws_scale would
                    // overflow the destination). For a file source this never fires; live
                    // streams need a texture recreate.
                    if (srcFrame.width() != w || srcFrame.height() != h) continue

                    // HW frame → CPU frame via av_hwframe_transfer_data. Software-decoded
                    // frames pass through unchanged.
                    val frameForSws = if (hwDecodeActive && srcFrame.format() == hwPixFmt) {
                        if (avutil.av_hwframe_transfer_data(swFrame, srcFrame, 0) < 0) continue
                        swFrame!!
                    } else srcFrame

                    // Lazy / on-format-change sws context — we only know the actual source
                    // pixel format once we see a frame (HW decoders emit a different CPU
                    // format than software for the same codec).
                    if (swsCtx == null || swsSrcFmt != frameForSws.format()) {
                        swsCtx?.let(swscale::sws_freeContext)
                        swsSrcFmt = frameForSws.format()
                        swsCtx = swscale.sws_getContext(
                            w, h, swsSrcFmt,
                            w, h, avutil.AV_PIX_FMT_BGRA,
                            swscale.SWS_BILINEAR, null, null, null as DoubleBuffer?
                        ) ?: error("sws_getContext failed (src_fmt=$swsSrcFmt)")
                    }
                    swscale.sws_scale(
                        swsCtx,
                        frameForSws.data(), frameForSws.linesize(), 0, h,
                        dstFrame.data(), dstFrame.linesize()
                    )
                    dstBuffer.position(0)
                    dstBuffer.get(scratch, 0, byteCount)
                    submitFrame(scratch, w, h)

                    val pts = srcFrame.best_effort_timestamp()
                    if (pts != avutil.AV_NOPTS_VALUE) {
                        positionMs = avutil.av_rescale_q(pts, stream.time_base(), avutil.av_make_q(1, 1000))
                            .coerceAtLeast(0L)
                    }
                    nextDeadline += frameInterval
                    val sleep = nextDeadline - System.currentTimeMillis()
                    if (sleep > 0) try { Thread.sleep(sleep) } catch (_: InterruptedException) { return }
                }
            }
        } catch (e: Throwable) {
            Logger.log(Logger.WARN, "FFmpegVideoTexture: decode loop failed: ${e.message}")
        } finally {
            // Tear down in reverse-allocation order. Each step is wrapped so an
            // already-freed pointer doesn't mask a real error in another step.
            closeAll(
                { swsCtx?.let(swscale::sws_freeContext) },
                { dstBuffer?.let(avutil::av_free) },
                { dstFrame?.let { avutil.av_frame_free(AVFrame(it)) } },
                { swFrame?.let { avutil.av_frame_free(AVFrame(it)) } },
                { srcFrame?.let { avutil.av_frame_free(AVFrame(it)) } },
                { packet?.let { avcodec.av_packet_free(AVPacket(it)) } },
                { codecCtx?.let { avcodec.avcodec_free_context(AVCodecContext(it)) } },
                { hwDeviceCtx?.let { avutil.av_buffer_unref(it) } },
                { fmtCtx?.let { avformat.avformat_close_input(AVFormatContext(it)) } },
            )
            hwDecodeActive = false
        }
    }

    /** Run each [actions] in sequence, swallowing any exception so one failure can't block the rest. */
    private fun closeAll(vararg actions: () -> Unit) {
        for (a in actions) runCatching(a)
    }

    /**
     * Pick a HW config the codec supports and create the device. Returns the
     * `(deviceCtx, config)` pair on success — caller attaches the device ctx and stashes
     * the GPU pixel format. Returns `null` when no HW config is compatible or device
     * creation fails (caller falls back to software decode).
     */
    private fun tryEnableHwDecode(codec: AVCodec, codecCtx: AVCodecContext): Pair<AVBufferRef, HwConfig>? {
        val cfg = pickHardwareConfig(codec) ?: return null
        val ref = AVBufferRef()
        val ret = avutil.av_hwdevice_ctx_create(ref, cfg.deviceType, null as String?, null, 0)
        if (ret < 0) {
            Logger.log(
                Logger.INFO,
                "FFmpegVideoTexture: HW device_type=${cfg.deviceType} init failed (ret=$ret); using software decode."
            )
            return null
        }
        codecCtx.hw_device_ctx(avutil.av_buffer_ref(ref))
        Logger.log(
            Logger.INFO,
            "FFmpegVideoTexture: HW decode enabled (device_type=${cfg.deviceType}, hw_pix_fmt=${cfg.pixFmt})."
        )
        return ref to cfg
    }

    /**
     * Walk the codec's HW configs and return the first one whose method advertises
     * `AV_HWDEVICE_CTX` support. Returns `null` when no compatible config exists. The
     * scan order is whatever FFmpeg has registered for the codec (typically D3D11VA →
     * DXVA2 on Windows, VAAPI on Linux, VideoToolbox on macOS); we don't filter by OS —
     * FFmpeg only lists configs that are buildable in the current natives.
     */
    private fun pickHardwareConfig(codec: AVCodec): HwConfig? {
        var i = 0
        while (true) {
            val cfg = avcodec.avcodec_get_hw_config(codec, i++) ?: return null
            if ((cfg.methods() and avcodec.AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) != 0) {
                return HwConfig(cfg.device_type(), cfg.pix_fmt())
            }
        }
    }
}
