package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.render.video.VlcjVideoTexture

/**
 * JVM video-on-terrain tutorial via [VlcjVideoTexture] (libVLC bindings). Requires VLC 3.0+
 * installed on the host; without it, [JvmVideoOnTerrainTutorial.start] catches the init
 * failure and silently no-ops.
 *
 * libVLC's MRL parser mangles `file:/C:/...` URIs on Windows (CWD prepend, demuxer falling
 * through to dvdnav) — pass the bare absolute path instead.
 */
class VlcjVideoOnTerrainTutorial(engine: WorldWind) :
    JvmVideoOnTerrainTutorial<VlcjVideoTexture>(engine, tag = "VLCJ") {

    override fun createPlayer(mediaPath: String) = VlcjVideoTexture(mediaPath, width = 640, height = 480)
}
