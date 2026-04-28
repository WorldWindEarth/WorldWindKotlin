package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.render.video.JavaCvVideoTexture

/**
 * JVM video-on-terrain tutorial via [JavaCvVideoTexture] (JavaCV's `FFmpegFrameGrabber`).
 * No external system requirement — `javacv-platform` bundles FFmpeg native binaries.
 */
class JavaCvVideoOnTerrainTutorial(engine: WorldWind) :
    JvmVideoOnTerrainTutorial<JavaCvVideoTexture>(engine, tag = "JavaCV") {

    override fun createPlayer(mediaPath: String) = JavaCvVideoTexture(mediaPath, width = 640, height = 480)
}
