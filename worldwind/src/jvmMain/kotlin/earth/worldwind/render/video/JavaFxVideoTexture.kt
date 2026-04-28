package earth.worldwind.render.video

import earth.worldwind.util.Logger
import earth.worldwind.util.kgl.GL_BGRA
import javafx.animation.AnimationTimer
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM video texture driven by JavaFX `MediaPlayer`. JavaFX has no per-frame callback API,
 * so we host the [MediaView] in an off-screen [Scene] (no [javafx.stage.Stage] attached)
 * and snapshot the view on every JavaFX [AnimationTimer] pulse — that's the standard
 * idiom for "give me the current video frame as pixels".
 *
 * The snapshot returns a [WritableImage]; we read its pixels in
 * [PixelFormat.getByteBgraInstance] format into a heap byte array and forward to the
 * [CallbackVideoTexture] base. The GL thread uploads on the next bind.
 *
 * **Runtime requirement:** OpenJFX 21+ runtime JARs (`javafx-base`, `javafx-graphics`,
 * `javafx-media`, `javafx-swing`) with the host platform classifier on the **module
 * path** — JavaFX rejects classpath loads since JDK 9.
 */
class JavaFxVideoTexture(
    /** Filesystem path or URL to the media. A bare path is wrapped via [File.toURI]. */
    val mediaUrl: String,
    width: Int, height: Int,
) : CallbackVideoTexture(width, height, uploadFormat = GL_BGRA), VideoPlayback {

    @Volatile private var mediaPlayer: MediaPlayer? = null
    @Volatile private var snapshotTimer: AnimationTimer? = null
    /**
     * Off-screen [Scene] held as a field so it doesn't fall out of scope when
     * [startMediaPipeline] returns. The Scene roots the StackPane → MediaView → MediaPlayer
     * chain; without this reference, JavaFX may evict its internal pulse handlers when
     * nothing roots the Scene, causing intermittent stalls on the snapshot loop.
     */
    @Volatile private var scene: Scene? = null
    @Volatile private var positionMs = 0L

    override fun play() {
        ensureJfxStarted()
        Platform.runLater {
            mediaPlayer?.also { it.play(); return@runLater }
            startMediaPipeline()
        }
    }
    override fun pause() { Platform.runLater { mediaPlayer?.pause() } }
    override fun stop() {
        Platform.runLater {
            snapshotTimer?.stop()
            mediaPlayer?.stop()
        }
    }
    override val timeMs: Long get() = positionMs
    override fun release() {
        Platform.runLater {
            snapshotTimer?.stop()
            snapshotTimer = null
            runCatching { mediaPlayer?.let { it.stop(); it.dispose() } }
            mediaPlayer = null
            scene = null
        }
    }

    private fun startMediaPipeline() {
        try {
            val mediaUri =
                if (mediaUrl.startsWith("file:") || mediaUrl.startsWith("http")) mediaUrl
                else File(mediaUrl).toURI().toString()
            val player = MediaPlayer(Media(mediaUri)).apply {
                isAutoPlay = true
                cycleCount = MediaPlayer.INDEFINITE
                setOnError {
                    Logger.log(Logger.WARN, "JavaFxVideoTexture: media error: ${error?.message}")
                }
            }
            mediaPlayer = player

            // Off-screen scene: MediaView in StackPane in Scene with no Stage. Node.snapshot
            // works for any node attached to a Scene — no real window required. Pinned to a
            // field so the Scene isn't GC-eligible when this function returns; otherwise
            // JavaFX may evict its pulse handlers and the snapshot loop intermittently stalls.
            val view = MediaView(player).apply {
                fitWidth = width.toDouble()
                fitHeight = height.toDouble()
                isPreserveRatio = false
            }
            scene = Scene(StackPane(view), width.toDouble(), height.toDouble())

            val params = SnapshotParameters()
            val img = WritableImage(width, height)
            val pixelFormat = PixelFormat.getByteBgraInstance()
            val scratch = ByteArray(width * height * 4)
            // Pace snapshots to ~30 fps. AnimationTimer fires every JFX pulse (~60 fps); we
            // throttle so half the JFX thread isn't doing snapshots.
            val frameIntervalNs = 1_000_000_000L / 30L
            var lastSnapshotNs = 0L

            snapshotTimer = object : AnimationTimer() {
                override fun handle(nowNs: Long) {
                    if (nowNs - lastSnapshotNs < frameIntervalNs) return
                    lastSnapshotNs = nowNs
                    view.snapshot(params, img)
                    img.pixelReader.getPixels(
                        0, 0, width, height, pixelFormat, scratch, 0, width * 4
                    )
                    submitFrame(scratch, width, height)
                    val t = player.currentTime
                    if (t != null && !t.isUnknown && !t.isIndefinite) {
                        positionMs = t.toMillis().toLong().coerceAtLeast(0L)
                    }
                }
            }.also { it.start() }
        } catch (e: Throwable) {
            Logger.log(Logger.WARN, "JavaFxVideoTexture: JFX init failed: ${e.message}")
        }
    }

    private companion object {
        private val jfxStarted = AtomicBoolean(false)

        /**
         * Start the JavaFX Application Thread once per process. `Platform.startup` throws
         * `IllegalStateException` if called twice; the AtomicBoolean dedupes. We never
         * call `Platform.exit` — that'd kill all future MediaPlayer instances.
         */
        fun ensureJfxStarted() {
            if (!jfxStarted.compareAndSet(false, true)) return
            runCatching {
                Platform.startup { /* JFX runtime ready */ }
                Platform.setImplicitExit(false)
            }
        }
    }
}
