package earth.worldwind.layer.graticule

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LABEL_COLOR
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LABEL_FONT
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LINE_COLOR
import earth.worldwind.render.Color
import earth.worldwind.render.Font
import earth.worldwind.render.FontWeight
import earth.worldwind.render.RenderContext
import kotlin.math.floor

/**
 * Displays the UTM graticule.
 */
open class UTMGraticuleLayer: AbstractUTMGraticuleLayer("UTM Graticule", 10000000, 1e6),
    GridTilesSupport.Callback {
    private val gridTilesSupport = GridTilesSupport(this, GRID_ROWS, GRID_COLS)
    override val orderedTypes = mutableListOf(GRATICULE_UTM_ZONE).apply { addAll(super.orderedTypes) }

    override fun initRenderingParams() {
        super.initRenderingParams()
        // UTM zone grid
        val params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(255, 255, 255) // White
        params[KEY_LABEL_COLOR] = Color(255, 255, 255) // White
        params[KEY_LABEL_FONT] = Font("arial", FontWeight.BOLD, 16)
        setRenderingParams(GRATICULE_UTM_ZONE, params)
    }

    override fun getTypeFor(resolution: Double) =
        if (resolution >= UTM_ZONE_RESOLUTION) GRATICULE_UTM_ZONE else super.getTypeFor(resolution)

    override fun selectRenderables(rc: RenderContext) {
        gridTilesSupport.selectRenderables(rc)
        super.selectRenderables(rc)
    }

    override fun createGridTile(sector: Sector): AbstractGraticuleTile =
        UTMGraticuleTile(this, sector, getGridColumn(sector.centroidLongitude) + 1)

    override fun getGridSector(row: Int, col: Int): Sector {
        val deltaLat = UTM_MAX_LATITUDE * 2.0 / GRID_ROWS
        val deltaLon = 360.0 / GRID_COLS
        val minLat = if (row == 0) UTM_MIN_LATITUDE else -UTM_MAX_LATITUDE + deltaLat * row
        val maxLat = -UTM_MAX_LATITUDE + deltaLat * (row + 1)
        val minLon = -180.0 + deltaLon * col
        val maxLon = minLon + deltaLon
        return fromDegrees(minLat, minLon, maxLat - minLat, maxLon - minLon)
    }

    override fun getGridColumn(longitude: Angle): Int {
        val deltaLon = 360.0 / GRID_COLS
        val col = floor((longitude.degrees + 180) / deltaLon).toInt()
        return col.coerceAtMost(GRID_COLS - 1)
    }

    override fun getGridRow(latitude: Angle): Int {
        val deltaLat = UTM_MAX_LATITUDE * 2.0 / GRID_ROWS
        val row = floor((latitude.degrees + UTM_MAX_LATITUDE) / deltaLat).toInt()
        return row.coerceIn(0, GRID_ROWS - 1)
    }

    companion object {
        const val UTM_ZONE_RESOLUTION = 5e5

        /** Graticule for the UTM zone grid.  */
        private const val GRATICULE_UTM_ZONE = "Graticule.UTM.Zone"
        private const val GRID_ROWS = 2
        private const val GRID_COLS = 60
    }

}