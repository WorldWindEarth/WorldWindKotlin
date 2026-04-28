package earth.worldwind.tutorials

import earth.worldwind.util.Logger
import java.io.File

/**
 * Lazy process-global staging of the bundled drone-motion MP4. JavaCV / FFmpeg / VLCJ /
 * JavaFX all need a real file path on disk (none accept a `jar:`-scheme URL), so each
 * tutorial used to extract its own copy from the JAR — switching between the four
 * tutorial entries triggered the same ~2.6 MB write four times.
 *
 * The first call extracts the asset to a temp file; subsequent calls (from the other
 * three tutorials, or from the same tutorial's [stop]/[start] cycle) return the same
 * cached path. The file is `deleteOnExit`-marked so the JVM cleans up when the tutorial
 * app shuts down.
 */
object SharedStagedVideo {

    @Volatile private var cached: File? = null

    /**
     * Returns a filesystem path to the bundled video, extracting once on first call.
     * Returns `null` if extraction fails — the caller should silently no-op.
     */
    @Synchronized
    fun path(): String? {
        cached?.takeIf { it.exists() && it.length() > 0 }?.let { return it.absolutePath }
        return try {
            val mp4 = MR.assets.video.drone_motion_mp4
            val src = mp4.resourcesClassLoader.getResourceAsStream(mp4.filePath) ?: run {
                Logger.log(Logger.WARN, "SharedStagedVideo: bundled ${mp4.filePath} not on classpath.")
                return null
            }
            File.createTempFile("drone_motion_", ".mp4").also { f ->
                f.deleteOnExit()
                src.use { input -> f.outputStream().use { input.copyTo(it) } }
                cached = f
            }.absolutePath
        } catch (e: Throwable) {
            Logger.log(Logger.WARN, "SharedStagedVideo: extraction failed: ${e.message}")
            null
        }
    }
}
