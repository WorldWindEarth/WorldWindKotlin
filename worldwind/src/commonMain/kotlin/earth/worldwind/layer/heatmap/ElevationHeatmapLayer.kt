package earth.worldwind.layer.heatmap

import earth.worldwind.draw.DrawableHeatmap
import earth.worldwind.layer.AbstractLayer
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext

open class ElevationHeatmapLayer: AbstractLayer("Elevation Heatmap") {
    override var isPickEnabled = false // Elevation Heatmap is not pickable
    override var opacity = 0.5f // Elevation Heatmap is semi-transparent by default
    /**
     * RGB colors for five thresholds of heatmap
     */
    val colors = arrayOf(
        Color(0.0f, 0.0f, 1.0f), // 0% - Blue
        Color(0.0f, 1.0f, 1.0f), // 25% - Cyan
        Color(0.0f, 1.0f, 0.0f), // 50% - Green
        Color(1.0f, 1.0f, 0.0f), // 75% - Yellow
        Color(1.0f, 0.0f, 0.0f)  // 100% - Red
    )
    /**
     * Auto determines height limits from available terrain tiles. If false, then limits should be specified manually.
     */
    var autoHeightLimits = true
    /**
     * Configurable offset from maximal available terrain tile level to take into account when calculating height limits
     */
    var levelNumberDepth = 3
    /**
     * Last automatically calculated or manually specified min and max height limits
     */
    val heightLimits = FloatArray(2)

    override fun doRender(rc: RenderContext) {
        if (autoHeightLimits) rc.terrain.heightLimits(levelNumberDepth, heightLimits)

        val pool = rc.getDrawablePool<DrawableHeatmap>()
        val drawable = DrawableHeatmap.obtain(pool)
        heightLimits.copyInto(drawable.heightLimits)
        drawable.colors.forEachIndexed { i, color -> color.copy(colors[i]) }
        drawable.opacity = opacity
        drawable.offset = rc.globe.offset
        drawable.program = rc.getShaderProgram { ElevationHeatmapProgram() }
        rc.offerSurfaceDrawable(drawable, 0.0)
    }
}
