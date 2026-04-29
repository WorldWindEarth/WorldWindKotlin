package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.render.Texture
import earth.worldwind.render.video.VideoPlayback
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.log

/**
 * Shared scaffold for the four JVM/desktop video-on-terrain tutorials (VLCJ, JavaCV,
 * FFmpeg, JavaFX). All four follow the same recipe — only the decoder backend changes:
 *
 *  1. Parse the bundled KLV timeline (moko-resources, same JSON for every backend).
 *  2. Stage the bundled MP4 once into a process-global temp file via [SharedStagedVideo]
 *     so switching tutorial entries doesn't re-extract the bytes from the JAR.
 *  3. Build a backend-specific player ([T] — a [Texture] that also implements
 *     [VideoPlayback]) from that path. If construction fails (missing native deps etc.)
 *     log a warning and silently no-op so the rest of the tutorial app stays usable.
 *  4. Wire the inner [VideoOnTerrainTutorial] (which owns the layer / quad / camera /
 *     timeline-driven corner animation) and start playback.
 *
 * Subclasses parametrize on their concrete texture type and implement [createPlayer] —
 * usually a single-line constructor call.
 */
abstract class JvmVideoOnTerrainTutorial<T>(
    protected val engine: WorldWind,
    /** Short label used as a prefix on log lines (e.g. `"VLCJ"`, `"JavaCV"`). */
    private val tag: String,
) : AbstractTutorial() where T : Texture, T : VideoPlayback {

    private val timeline = KlvTimeline.parse(MR.assets.video.drone_motion_json.readText())
    private var inner: VideoOnTerrainTutorial? = null
    private var player: T? = null

    /** Construct the backend-specific player from the staged MP4 path. */
    protected abstract fun createPlayer(mediaPath: String): T

    final override fun start() {
        super.start()
        val mediaPath = SharedStagedVideo.path() ?: run {
            log(Logger.WARN, "$tag: shared video staging failed; tutorial inactive.")
            return
        }
        val p = try {
            createPlayer(mediaPath)
        } catch (e: Throwable) {
            log(Logger.WARN, "$tag: player init failed: ${e.message}")
            return
        }
        player = p
        inner = VideoOnTerrainTutorial(
            engine = engine,
            timeline = timeline,
            texture = p,
            currentTimeMs = { p.timeMs.coerceAtLeast(0L) },
        ).also { it.start() }
        p.play()
    }

    final override fun stop() {
        super.stop()
        runCatching { player?.release() }
        inner?.stop()
        inner = null
        player = null
    }

    // Surface the inner tutorial's UI actions (currently the 3D-projection toggle) to the
    // JVM tutorial host so they render as buttons next to the dropdown. Read AFTER `start`
    // so `inner` is non-null; the host's call sequence is start() then `actions`.
    override val actions: ArrayList<String>? get() = inner?.actions

    final override fun runAction(actionName: String) { inner?.runAction(actionName) }
}
