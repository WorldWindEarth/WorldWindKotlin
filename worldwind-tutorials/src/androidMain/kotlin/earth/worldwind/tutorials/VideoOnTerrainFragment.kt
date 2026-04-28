package earth.worldwind.tutorials

import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import android.os.Build
import android.widget.CheckBox
import earth.worldwind.WorldWind
import earth.worldwind.WorldWindow
import earth.worldwind.render.video.MediaSurfaceTexture

/**
 * Android tutorial: drapes a real STANAG 4609 drone clip onto the terrain via the OES
 * external-texture path (`MediaPlayer` -> `SurfaceTexture` -> GL_TEXTURE_EXTERNAL_OES) and
 * animates the projected footprint with the embedded KLV telemetry.
 *
 * Lifecycle is GL-thread / surface-driven:
 *  1. Fragment is created; we open the bundled MP4 directly via [AssetFileDescriptor] (no
 *     cache copy), parse the bundled KLV JSON, construct a [MediaSurfaceTexture], and start
 *     [VideoOnTerrainTutorial]. That adds a [earth.worldwind.shape.ProjectedMediaSurface]
 *     holding the OES texture to a layer.
 *  2. On the first render frame the engine binds the OES texture, allocating the GL name
 *     and firing [MediaSurfaceTexture.onSurfaceReady] on the GL thread.
 *  3. We hop to the main thread (MediaPlayer needs a Looper) and start playback against
 *     that Surface.
 *  4. [MediaSurfaceTexture.onFrameAvailable] (decoder thread) requests a redraw so the GL
 *     thread re-runs and `updateTexImage` pulls the new frame.
 */
class VideoOnTerrainFragment : BasicGlobeFragment() {

    private var mediaPlayer: MediaPlayer? = null
    private var videoTexture: MediaSurfaceTexture? = null
    private var tutorial: VideoOnTerrainTutorial? = null
    private var videoAssetFd: AssetFileDescriptor? = null

    override fun createWorldWindow(): WorldWindow {
        val wwd = super.createWorldWindow()

        // Pre-extracted KLV timeline JSON, bundled cross-platform via moko-resources at
        // `MR.assets.video.drone_motion_json`. moko's Android AssetResource exposes a
        // `readText(context)` helper that goes through the platform's AssetManager.
        val timeline = KlvTimeline.parse(
            MR.assets.video.drone_motion_json.readText(requireContext())
        )

        // Open the bundled MP4 directly as an AssetFileDescriptor so MediaPlayer streams
        // out of the APK with no cache-dir copy. The asset is pre-compressed (H.264 in
        // MP4), so AAPT keeps it uncompressed in the APK and `openFd` returns a valid
        // offset/length pair. The FD must outlive the player; we close it in onDestroyView.
        val videoFd = requireContext().assets
            .openFd(MR.assets.video.drone_motion_mp4.originalPath)
            .also { videoAssetFd = it }

        // Allocate the OES texture (the GL name itself is created lazily on the first
        // bindTexture). Width/height are advisory — producer frame dimensions win on
        // each updateTexImage.
        // MediaSurfaceTexture calls WorldWind.requestRedraw() internally on each frame
        // (and re-fires onSurfaceReady after a GL context loss), so the fragment doesn't
        // need to wire either hook unless it has additional per-frame work to do.
        val tex = MediaSurfaceTexture(width = 640, height = 480).also { videoTexture = it }

        // Surface becomes available on the GL thread after the texture name is allocated.
        // Fires once at startup, and again after a GL context loss with a fresh Surface
        // bound to the regenerated GL name. Hop to main (MediaPlayer needs a Looper) and
        // either construct the player (initial) or just rebind it (post-context-loss).
        tex.onSurfaceReady = {
            wwd.post {
                val surface = tex.surface ?: return@post
                val existing = mediaPlayer
                if (existing != null) {
                    // Context-loss recovery — rebind the existing decoder to the new Surface.
                    try { existing.setSurface(surface) } catch (_: Throwable) { /* idempotent */ }
                } else {
                    mediaPlayer = MediaPlayer().apply {
                        setSurface(surface)
                        setDataSource(videoFd.fileDescriptor, videoFd.startOffset, videoFd.length)
                        isLooping = true
                        setOnPreparedListener { it.start() }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            prepareAsync()
                        } else {
                            @Suppress("DEPRECATION")
                            prepare()
                            start()
                        }
                    }
                }
                WorldWind.requestRedraw()
            }
        }

        // Start the inner tutorial up-front. It adds the ProjectedMediaSurface with `tex` to a layer;
        // the first render frame will bind the OES texture (allocating its GL name), which
        // triggers the onSurfaceReady wiring above. Until the MediaPlayer is constructed
        // the time source returns 0, so the quad sits at the timeline's first sample.
        tutorial = VideoOnTerrainTutorial(
            engine = wwd.engine,
            timeline = timeline,
            texture = tex,
            currentTimeMs = {
                try { mediaPlayer?.currentPosition?.toLong()?.coerceAtLeast(0L) ?: 0L }
                catch (_: IllegalStateException) { 0L }
            },
            telemetryDelayMs = VideoOnTerrainTutorial.BUNDLED_DRONE_MOTION_DELAY_MS,
        ).also { it.start() }

        return wwd
    }

    override fun onPause() {
        super.onPause()
        // Avoid decoding behind a paused GLSurfaceView — and on resume the GL context may
        // have been torn down so the texture name will be invalidated; MediaPlayer will be
        // re-bound to a fresh Surface via onSurfaceReady.
        try { mediaPlayer?.pause() } catch (_: IllegalStateException) { /* idempotent */ }
        // Hide the 3D toggle the activity hosts in its toolbar - it belongs to this
        // fragment only. Other tutorials re-use the same toolbar without it.
        toolbar3dCheckbox()?.apply {
            setOnCheckedChangeListener(null)
            visibility = android.view.View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        try { mediaPlayer?.start() } catch (_: IllegalStateException) { /* idempotent */ }
        // Wire the activity's toolbar 3D toggle to this tutorial. Visible alongside the
        // existing 2D projection checkbox so all globe controls share one row.
        toolbar3dCheckbox()?.apply {
            visibility = android.view.View.VISIBLE
            isChecked = tutorial?.useCameraProjection == true
            setOnCheckedChangeListener { _, on -> tutorial?.useCameraProjection = on }
        }
    }

    /** Resolve the 3D-toggle checkbox declared in `activity_main.xml`. */
    private fun toolbar3dCheckbox(): CheckBox? = activity?.findViewById(R.id.is3d)

    override fun onDestroyView() {
        super.onDestroyView()
        // Tear the producer down before the consumer to avoid races during decoder shutdown.
        try { mediaPlayer?.release() } catch (_: Throwable) { /* best effort */ }
        mediaPlayer = null
        tutorial?.stop()
        tutorial = null
        // MediaSurfaceTexture's Surface/SurfaceTexture are released inside its
        // deleteTexture override when the engine drops the layer/texture.
        videoTexture = null
        runCatching { videoAssetFd?.close() }
        videoAssetFd = null
    }
}
