package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.render.video.JavaFxVideoTexture

/**
 * JVM video-on-terrain tutorial via [JavaFxVideoTexture] (JavaFX `MediaPlayer` +
 * off-screen `MediaView` snapshot loop). Bundles JavaFX's gstreamer-based decoder.
 *
 * **Run requirement:** JavaFX must be on the **module path** since JDK 9 — use
 * `./gradlew :worldwind-tutorials:runJvmTutorials` (the task auto-configures
 * `--module-path` from the resolved `javafx-*.jar` files).
 */
class JavaFxVideoOnTerrainTutorial(engine: WorldWind) :
    JvmVideoOnTerrainTutorial<JavaFxVideoTexture>(engine, tag = "JavaFX") {

    override fun createPlayer(mediaPath: String) = JavaFxVideoTexture(mediaPath, width = 640, height = 480)
}
