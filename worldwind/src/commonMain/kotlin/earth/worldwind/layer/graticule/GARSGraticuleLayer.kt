package earth.worldwind.layer.graticule

import earth.worldwind.geom.Sector
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LABEL_COLOR
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LABEL_FONT
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LINE_COLOR
import earth.worldwind.render.Color
import earth.worldwind.render.Font
import earth.worldwind.render.FontWeight

/**
 * Displays the geographic Global Area Reference System (GARS) graticule. The graticule has four levels. The first level
 * displays lines of latitude and longitude. The second level displays 30 minute square grid cells. The third level
 * displays 15 minute grid cells. The fourth and final level displays 5 minute grid cells.
 *
 * This graticule is intended to be used on 2D globes because it is so dense.
 */
open class GARSGraticuleLayer: AbstractLatLonGraticuleLayer("GARS Graticule") {
    override val orderedTypes = listOf(
        GRATICULE_GARS_LEVEL_0,
        GRATICULE_GARS_LEVEL_1,
        GRATICULE_GARS_LEVEL_2,
        GRATICULE_GARS_LEVEL_3
    )

    override fun initRenderingParams() {
        // Ten degrees grid
        var params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(255, 255, 255) // White
        params[KEY_LABEL_COLOR] = Color(255, 255, 255) // White
        params[KEY_LABEL_FONT] = Font("arial", FontWeight.BOLD, 16)
        setRenderingParams(GRATICULE_GARS_LEVEL_0, params)
        // One degree
        params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(255, 255, 0) // Yellow
        params[KEY_LABEL_COLOR] = Color(255, 255, 0) // Yellow
        params[KEY_LABEL_FONT] = Font("arial", FontWeight.BOLD, 14)
        setRenderingParams(GRATICULE_GARS_LEVEL_1, params)
        // 1/10th degree - 1/6th (10 minutes)
        params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(0, 255, 0) // Green
        params[KEY_LABEL_COLOR] = Color(0, 255, 0) // Green
        setRenderingParams(GRATICULE_GARS_LEVEL_2, params)
        // 1/100th degree - 1/60th (one minutes)
        params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(0, 255, 255) // Cyan
        params[KEY_LABEL_COLOR] = Color(0, 255, 255) // Cyan
        setRenderingParams(GRATICULE_GARS_LEVEL_3, params)
    }

    override fun getTypeFor(resolution: Double) =
        when {
            resolution >= 10 -> GRATICULE_GARS_LEVEL_0
            resolution >= 0.5 -> GRATICULE_GARS_LEVEL_1
            resolution >= .25 -> GRATICULE_GARS_LEVEL_2
            resolution >= 5.0 / 60.0 -> GRATICULE_GARS_LEVEL_3
            else -> GRATICULE_GARS_LEVEL_3
        }

    override fun createGridTile(sector: Sector): AbstractGraticuleTile = GARSGraticuleTile(this, sector, 20, 0)

    companion object {
        private const val GRATICULE_GARS_LEVEL_0 = "Graticule.GARSLevel0"
        private const val GRATICULE_GARS_LEVEL_1 = "Graticule.GARSLevel1"
        private const val GRATICULE_GARS_LEVEL_2 = "Graticule.GARSLevel2"
        private const val GRATICULE_GARS_LEVEL_3 = "Graticule.GARSLevel3"
    }
}