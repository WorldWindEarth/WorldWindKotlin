package earth.worldwind.layer.graticule

import earth.worldwind.geom.Sector
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LABEL_COLOR
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LABEL_FONT
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LINE_COLOR
import earth.worldwind.render.Color
import earth.worldwind.render.Font
import earth.worldwind.render.FontWeight

/**
 * Displays the geographic latitude/longitude graticule.
 */
open class LatLonGraticuleLayer : AbstractLatLonGraticuleLayer("LatLon Graticule") {
    override val orderedTypes = listOf(
        GRATICULE_LATLON_LEVEL_0,
        GRATICULE_LATLON_LEVEL_1,
        GRATICULE_LATLON_LEVEL_2,
        GRATICULE_LATLON_LEVEL_3,
        GRATICULE_LATLON_LEVEL_4,
        GRATICULE_LATLON_LEVEL_5
    )

    override fun initRenderingParams() {
        // Ten degrees grid
        var params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(255, 255, 255) // White
        params[KEY_LABEL_COLOR] = Color(255, 255, 255) // White
        params[KEY_LABEL_FONT] = Font("arial", FontWeight.BOLD, 16)
        setRenderingParams(GRATICULE_LATLON_LEVEL_0, params)
        // One degree
        params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(0, 255, 0) // Green
        params[KEY_LABEL_COLOR] = Color(0, 255, 0) // Green
        params[KEY_LABEL_FONT] = Font("arial", FontWeight.BOLD, 14)
        setRenderingParams(GRATICULE_LATLON_LEVEL_1, params)
        // 1/10th degree - 1/6th (10 minutes)
        params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(0, 102, 255)
        params[KEY_LABEL_COLOR] = Color(0, 102, 255)
        setRenderingParams(GRATICULE_LATLON_LEVEL_2, params)
        // 1/100th degree - 1/60th (one minutes)
        params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(0, 255, 255) // Cyan
        params[KEY_LABEL_COLOR] = Color(0, 255, 255) // Cyan
        setRenderingParams(GRATICULE_LATLON_LEVEL_3, params)
        // 1/1000 degree - 1/360th (10 seconds)
        params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(0, 153, 153)
        params[KEY_LABEL_COLOR] = Color(0, 153, 153)
        setRenderingParams(GRATICULE_LATLON_LEVEL_4, params)
        // 1/10000 degree - 1/3600th (one second)
        params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(102, 255, 204)
        params[KEY_LABEL_COLOR] = Color(102, 255, 204)
        setRenderingParams(GRATICULE_LATLON_LEVEL_5, params)
    }

    override fun getTypeFor(resolution: Double): String {
        return when {
            resolution >= 10 -> GRATICULE_LATLON_LEVEL_0
            resolution >= 1 -> GRATICULE_LATLON_LEVEL_1
            resolution >= .1 -> GRATICULE_LATLON_LEVEL_2
            resolution >= .01 -> GRATICULE_LATLON_LEVEL_3
            resolution >= .001 -> GRATICULE_LATLON_LEVEL_4
            resolution >= .0001 -> GRATICULE_LATLON_LEVEL_5
            else -> GRATICULE_LATLON_LEVEL_5
        }
    }

    override fun createGridTile(sector: Sector): AbstractGraticuleTile = LatLonGraticuleTile(this, sector, 10, 0)

    companion object {
        private const val GRATICULE_LATLON_LEVEL_0 = "Graticule.LatLonLevel0"
        private const val GRATICULE_LATLON_LEVEL_1 = "Graticule.LatLonLevel1"
        private const val GRATICULE_LATLON_LEVEL_2 = "Graticule.LatLonLevel2"
        private const val GRATICULE_LATLON_LEVEL_3 = "Graticule.LatLonLevel3"
        private const val GRATICULE_LATLON_LEVEL_4 = "Graticule.LatLonLevel4"
        private const val GRATICULE_LATLON_LEVEL_5 = "Graticule.LatLonLevel5"
    }
}