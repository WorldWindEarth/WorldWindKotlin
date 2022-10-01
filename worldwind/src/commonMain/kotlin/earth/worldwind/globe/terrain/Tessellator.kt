package earth.worldwind.globe.terrain

import earth.worldwind.render.RenderContext

interface Tessellator {
    val lastTerrain: Terrain
    fun tessellate(rc: RenderContext)
}