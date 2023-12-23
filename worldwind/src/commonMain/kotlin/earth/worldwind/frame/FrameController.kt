package earth.worldwind.frame

import earth.worldwind.draw.DrawContext
import earth.worldwind.globe.Globe
import earth.worldwind.globe.terrain.Terrain
import earth.worldwind.render.RenderContext

interface FrameController {
    val lastTerrains: Map<Globe.Offset, Terrain>
    fun renderFrame(rc: RenderContext)
    fun drawFrame(dc: DrawContext)
}