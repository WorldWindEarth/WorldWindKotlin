package earth.worldwind.layer.heatmap

import earth.worldwind.draw.DrawableGroup
import earth.worldwind.layer.AbstractLayer
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext

open class ElevationHeatmapLayer: AbstractLayer("Elevation Heatmap") {
    override var isPickEnabled = false // Elevation Heatmap is not pickable
    override var opacity = 0.5f // Elevation Heatmap is semi-transparent by default
    /**
     * RGB colors for 5 thresholds of heatmap
     */
    val colors = arrayOf(
        Color(0.0f, 0.0f, 1.0f), // 0% - Blue
        Color(0.0f, 1.0f, 1.0f), // 25% - Cyan
        Color(0.0f, 1.0f, 0.0f), // 50% - Green
        Color(1.0f, 1.0f, 0.0f), // 75% - Yellow
        Color(1.0f, 0.0f, 0.0f)  // 100% - Red
    )
    /**
     * Auto determine height limits from available terrain tiles. If false, then limits should be specified manually.
     */
    var autoHeightLimits = true
    /**
     * Configurable offset from maximal available terrain tile level to take into account when calculating height limits
     */
    var levelNumberDepth = 3
    /**
     * Last automatically calculated or manually specified min and max height limits
     */

    override fun doRender(rc: RenderContext) {
        val heightLimits = FloatArray(2)
        if (autoHeightLimits) rc.terrain.heightLimits(levelNumberDepth, heightLimits)

        val program = rc.getShaderProgram { ElevationHeatmapProgram() }
        val offset = rc.globe.offset
        rc.offerDrawableLambda(DrawableGroup.SURFACE, 0.0) { dc ->
            if (!program.useProgram(dc)) return@offerDrawableLambda // program failed to build

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
}
