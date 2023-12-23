package earth.worldwind.draw

import earth.worldwind.globe.Globe
import earth.worldwind.layer.heatmap.ElevationHeatmapProgram
import earth.worldwind.render.Color
import earth.worldwind.util.Pool
import kotlin.jvm.JvmStatic

open class DrawableHeatmap protected constructor(): Drawable {
    val heightLimits = FloatArray(2)
    val colors = Array(5) { Color() }
    var opacity = 1.0f
    var offset = Globe.Offset.Center
    var program: ElevationHeatmapProgram? = null
    private var pool: Pool<DrawableHeatmap>? = null

    companion object {
        @JvmStatic
        fun obtain(pool: Pool<DrawableHeatmap>): DrawableHeatmap {
            val instance = pool.acquire() ?: DrawableHeatmap()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        program = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val program = program ?: return // program unspecified
        if (!program.useProgram(dc)) return // program failed to build

        try {
            dc.gl.enableVertexAttribArray(1)

            program.setLimits(heightLimits)
            program.setColors(colors)
            program.setOpacity(opacity)

            for (idx in 0 until dc.drawableTerrainCount) {
                // Get the drawable terrain associated with the draw context.
                val terrain = dc.getDrawableTerrain(idx)
                if (terrain.offset != offset) continue

                // Get the terrain's attributes, and keep a flag to ensure we apply the terrain's attributes at most once.
                val terrainOrigin = terrain.vertexOrigin

                // Use the terrain's vertex point attribute and vertex tex coord attribute.
                if (
                    terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/) &&
                    terrain.useVertexHeightsAttrib(dc, 1 /*vertexHeights*/)
                ) {
                    // Use the draw context's modelview projection matrix, transformed to terrain local coordinates.
                    program.mvpMatrix.copy(dc.modelviewProjection)
                    program.mvpMatrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
                    program.loadModelviewProjection()
                } else continue // terrain vertex attribute failed to bind

                // Draw the terrain as triangles.
                terrain.drawTriangles(dc)
            }
        } finally {
            dc.gl.disableVertexAttribArray(1)
        }
    }
}