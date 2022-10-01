package earth.worldwind.layer.graticule

import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.coords.Hemisphere
import earth.worldwind.geom.coords.UPSCoord.Companion.fromUPS
import earth.worldwind.geom.coords.UTMCoord.Companion.fromUTM
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LABEL_COLOR
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LABEL_FONT
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LINE_COLOR
import earth.worldwind.render.Color
import earth.worldwind.render.Font
import earth.worldwind.render.FontWeight
import earth.worldwind.render.RenderContext
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Displays the UTM graticule metric scale.
 */
abstract class AbstractUTMGraticuleLayer(name: String, scaleModulo: Int, maxResolution: Double): AbstractGraticuleLayer(name) {
    private val metricScaleSupport = UTMMetricScaleSupport(this).apply {
        this.scaleModulo = scaleModulo
        this.maxResolution = maxResolution
    }

    override val orderedTypes = listOf(
        GRATICULE_UTM_100000M,
        GRATICULE_UTM_10000M,
        GRATICULE_UTM_1000M,
        GRATICULE_UTM_100M,
        GRATICULE_UTM_10M,
        GRATICULE_UTM_1M
    )

    override fun initRenderingParams() {
        // 100,000 meter graticule
        var params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(0, 255, 0) // Green
        params[KEY_LABEL_COLOR] = Color(0, 255, 0) // Green
        params[KEY_LABEL_FONT] = Font("arial", FontWeight.BOLD, 14)
        setRenderingParams(GRATICULE_UTM_100000M, params)
        // 10,000 meter graticule
        params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(0, 102, 255)
        params[KEY_LABEL_COLOR] = Color(0, 102, 255)
        setRenderingParams(GRATICULE_UTM_10000M, params)
        // 1,000 meter graticule
        params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(0, 255, 255) // Cyan
        params[KEY_LABEL_COLOR] = Color(0, 255, 255) // Cyan
        setRenderingParams(GRATICULE_UTM_1000M, params)
        // 100 meter graticule
        params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(0, 153, 153)
        params[KEY_LABEL_COLOR] = Color(0, 153, 153)
        setRenderingParams(GRATICULE_UTM_100M, params)
        // 10 meter graticule
        params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(102, 255, 204)
        params[KEY_LABEL_COLOR] = Color(102, 255, 204)
        setRenderingParams(GRATICULE_UTM_10M, params)
        // 1 meter graticule
        params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(153, 153, 255)
        params[KEY_LABEL_COLOR] = Color(153, 153, 255)
        setRenderingParams(GRATICULE_UTM_1M, params)
    }

    override fun getTypeFor(resolution: Double) =
        when {
            resolution >= 100000 -> GRATICULE_UTM_100000M
            resolution >= 10000 -> GRATICULE_UTM_10000M
            resolution >= 1000 -> GRATICULE_UTM_1000M
            resolution >= 100 -> GRATICULE_UTM_100M
            resolution >= 10 -> GRATICULE_UTM_10M
            resolution >= 1 -> GRATICULE_UTM_1M
            else -> GRATICULE_UTM_1M
        }

    override fun clear(rc: RenderContext) {
        super.clear(rc)
        metricScaleSupport.clear()
        metricScaleSupport.computeZone(rc)
    }

    override fun selectRenderables(rc: RenderContext) { metricScaleSupport.selectRenderables(rc) }

    fun computeMetricScaleExtremes(UTMZone: Int, hemisphere: Hemisphere, ge: GridElement, size: Double) {
        metricScaleSupport.computeMetricScaleExtremes(UTMZone, hemisphere, ge, size)
    }

    fun computePosition(zone: Int, hemisphere: Hemisphere, easting: Double, northing: Double): Position {
        return if (zone > 0) computePositionFromUTM(zone, hemisphere, easting, northing)
        else computePositionFromUPS(hemisphere, easting, northing)
    }

    private fun computePositionFromUTM(zone: Int, hemisphere: Hemisphere, easting: Double, northing: Double): Position {
        val utm = fromUTM(zone, hemisphere, easting, northing)
        return Position(utm.latitude.clampLatitude(), utm.longitude.clampLongitude(), 10e3)
    }

    private fun computePositionFromUPS(hemisphere: Hemisphere, easting: Double, northing: Double): Position {
        val ups = fromUPS(hemisphere, easting, northing)
        return Position(ups.latitude.clampLatitude(), ups.longitude.clampLongitude(), 10e3)
    }

    fun createSquaresGrid(
        utmZone: Int, hemisphere: Hemisphere, utmZoneSector: Sector,
        minEasting: Double, maxEasting: Double, minNorthing: Double, maxNorthing: Double
    ): MutableList<UTMSquareZone> {
        val squares = mutableListOf<UTMSquareZone>()
        val startEasting = floor(minEasting / ONEHT) * ONEHT
        val startNorthing = floor(minNorthing / ONEHT) * ONEHT
        val cols = ceil((maxEasting - startEasting) / ONEHT).toInt()
        val rows = ceil((maxNorthing - startNorthing) / ONEHT).toInt()
        val squaresArray = Array(rows) { arrayOfNulls<UTMSquareZone>(cols) }
        var col = 0
        var easting = startEasting
        while (easting < maxEasting) {
            var row = 0
            var northing = startNorthing
            while (northing < maxNorthing) {
                val sz = UTMSquareZone(this, utmZone, hemisphere, utmZoneSector, easting, northing, ONEHT)
                if (!sz.isOutsideGridZone) {
                    squares.add(sz)
                    squaresArray[row][col] = sz
                }
                row++
                northing += ONEHT
            }
            col++
            easting += ONEHT
        }

        // Keep track of neighbors
        for (c in 0 until cols) {
            for (r in 0 until rows) {
                val sz = squaresArray[r][c]
                if (sz != null) {
                    sz.northNeighbor = if (r + 1 < rows) squaresArray[r + 1][c] else null
                    sz.eastNeighbor = if (c + 1 < cols) squaresArray[r][c + 1] else null
                }
            }
        }
        return squares
    }

    companion object {
        const val UTM_MIN_LATITUDE = -80.0
        const val UTM_MAX_LATITUDE = 84.0

        /** Graticule for the 100,000 meter grid.  */
        private const val GRATICULE_UTM_100000M = "Graticule.UTM.100000m"

        /** Graticule for the 10,000 meter grid.  */
        private const val GRATICULE_UTM_10000M = "Graticule.UTM.10000m"

        /** Graticule for the 1,000 meter grid.  */
        private const val GRATICULE_UTM_1000M = "Graticule.UTM.1000m"

        /** Graticule for the 100 meter grid.  */
        private const val GRATICULE_UTM_100M = "Graticule.UTM.100m"

        /** Graticule for the 10 meter grid.  */
        private const val GRATICULE_UTM_10M = "Graticule.UTM.10m"

        /** Graticule for the 1 meter grid.  */
        private const val GRATICULE_UTM_1M = "Graticule.UTM.1m"
        private const val ONEHT = 100e3
    }
}