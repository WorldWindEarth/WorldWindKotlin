package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.render.video.FFmpegVideoTexture

/**
 * JVM video-on-terrain tutorial via [FFmpegVideoTexture] (raw javacpp-presets FFmpeg, no
 * JavaCV wrapper). Useful as a reference for what the JavaCV wrapper hides — codec
 * selection, packet/frame management, sws_scale BGRA conversion, hardware-accelerated
 * decoding (DXVA2/D3D11VA/VAAPI/VideoToolbox), wall-clock pacing.
 */
class FFmpegVideoOnTerrainTutorial(engine: WorldWind) :
    JvmVideoOnTerrainTutorial<FFmpegVideoTexture>(engine, tag = "FFmpeg") {

    override fun createPlayer(mediaPath: String) = FFmpegVideoTexture(mediaPath, width = 640, height = 480)
}
