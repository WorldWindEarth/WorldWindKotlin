package earth.worldwind.globe.terrain

import earth.worldwind.render.RenderContext

interface Tessellator {
    fun tessellate(rc: RenderContext): Terrain
}