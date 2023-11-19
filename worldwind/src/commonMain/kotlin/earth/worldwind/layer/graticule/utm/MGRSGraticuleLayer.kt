package earth.worldwind.layer.graticule.utm

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.geom.Viewport
import earth.worldwind.layer.graticule.GraticuleRenderingParams
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_DRAW_LABELS
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LABEL_COLOR
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LABEL_FONT
import earth.worldwind.layer.graticule.GraticuleRenderingParams.Companion.KEY_LINE_COLOR
import earth.worldwind.render.Color
import earth.worldwind.render.Font
import earth.worldwind.render.FontWeight
import earth.worldwind.render.RenderContext
import kotlin.math.floor

open class MGRSGraticuleLayer: AbstractUTMGraticuleLayer("MGRS Graticule", 100000, 1e5) {
    private val gridZones = Array(20) { arrayOfNulls<MGRSGridZone>(60) } // row/col
    private val poleZones = arrayOfNulls<MGRSGridZone>(4) // North x2 + South x2
    private val overview = MGRSOverview(this)

    /**
     * The maximum resolution graticule that will be rendered, or null if no graticules will be rendered. By
     * default, all graticules are rendered, and this will return GRATICULE_1M.
     */
    var maximumGraticuleResolution: String?
        get() {
            var maxTypeDrawn: String? = null
            for (i in orderedTypes.indices) {
                val type = orderedTypes[i]
                val params = getRenderingParams(type)
                if (params.isDrawLines) maxTypeDrawn = type
            }
            return maxTypeDrawn
        }
        set(graticuleType) {
            var pastTarget = false
            for (i in orderedTypes.indices) {
                val type = orderedTypes[i]
                // Enable all graticulte BEFORE and INCLUDING the target.
                // Disable all graticules AFTER the target.
                val params = getRenderingParams(type)
                params.isDrawLines = !pastTarget
                params.isDrawLabels = !pastTarget
                if (!pastTarget && type == graticuleType) pastTarget = true
            }
        }

    override val orderedTypes = mutableListOf(GRATICULE_MGRS_OVERVIEW, GRATICULE_MGRS_GRID_ZONE).apply { addAll(super.orderedTypes) }

    override fun initRenderingParams() {
        super.initRenderingParams()
        // MGRS Overview graticule
        var params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(.8f, .8f, .8f, .5f)
        params[KEY_LABEL_COLOR] = Color(1f, 1f, 1f, .8f)
        params[KEY_LABEL_FONT] = Font("arial", FontWeight.BOLD, 14)
        params[KEY_DRAW_LABELS] = true
        setRenderingParams(GRATICULE_MGRS_OVERVIEW, params)
        // MGRS GridZone graticule
        params = GraticuleRenderingParams()
        params[KEY_LINE_COLOR] = Color(255, 255, 0) // Yellow
        params[KEY_LABEL_COLOR] = Color(255, 255, 0) // Yellow
        params[KEY_LABEL_FONT] = Font("arial", FontWeight.BOLD, 16)
        setRenderingParams(GRATICULE_MGRS_GRID_ZONE, params)
    }

    override fun getTypeFor(resolution: Double) =
        when (resolution) {
            MGRS_OVERVIEW_RESOLUTION -> GRATICULE_MGRS_OVERVIEW
            MGRS_GRID_ZONE_RESOLUTION -> GRATICULE_MGRS_GRID_ZONE
            else -> super.getTypeFor(resolution)
        }

    override fun selectRenderables(rc: RenderContext) {
        if (rc.camera.position.altitude <= GRID_ZONE_MAX_ALTITUDE) {
            selectMGRSRenderables(rc)
            super.selectRenderables(rc)
        } else {
            overview.selectRenderables(rc)
        }
    }

    private fun selectMGRSRenderables(rc: RenderContext) {
        val zoneList = getVisibleZones(rc)
        for (gz in zoneList) gz.selectRenderables(rc)
    }

    private fun getVisibleZones(rc: RenderContext): List<MGRSGridZone> {
        val zoneList = mutableListOf<MGRSGridZone>()
        val vs = rc.terrain.sector
        // UTM Grid
        val gridRectangle = getGridRectangleForSector(vs)
        if (gridRectangle != null) {
            for (row in gridRectangle.y..gridRectangle.height) {
                for (col in gridRectangle.x..gridRectangle.width) {
                    if (row != 19 || col != 31 && col != 33 && col != 35) {
                        // ignore X32, 34 and 36
                        val zone = gridZones[row][col] ?: MGRSGridZone(this, getGridSector(row, col)).also { gridZones[row][col] = it }
                        if (zone.isInView(rc)) zoneList.add(zone) else zone.clearRenderables()
                    }
                }
            }
        }
        // Poles
        if (vs.maxLatitude.inDegrees > 84) {
            // North pole
            if (poleZones[2] == null) poleZones[2] = MGRSGridZone(this, fromDegrees(84.0, -180.0, 6.0, 180.0)) // Y
            if (poleZones[3] == null) poleZones[3] = MGRSGridZone(this, fromDegrees(84.0, 0.0, 6.0, 180.0)) // Z
            zoneList.add(poleZones[2]!!)
            zoneList.add(poleZones[3]!!)
        }
        if (vs.minLatitude.inDegrees < -80) {
            // South pole
            if (poleZones[0] == null) poleZones[0] = MGRSGridZone(this, fromDegrees(-90.0, -180.0, 10.0, 180.0)) // B
            if (poleZones[1] == null) poleZones[1] = MGRSGridZone(this, fromDegrees(-90.0, 0.0, 10.0, 180.0)) // A
            zoneList.add(poleZones[0]!!)
            zoneList.add(poleZones[1]!!)
        }
        return zoneList
    }

    private fun getGridRectangleForSector(sector: Sector): Viewport? {
        var rectangle: Viewport? = null
        if (sector.minLatitude.inDegrees < 84 && sector.maxLatitude.inDegrees > -80) {
            val minLat = sector.minLatitude.inDegrees.coerceAtLeast(-80.0)
            val maxLat = sector.maxLatitude.inDegrees.coerceAtMost(84.0)
            val gridSector = fromDegrees(
                minLat, sector.minLongitude.inDegrees, maxLat - minLat, sector.deltaLongitude.inDegrees
            )
            var x1 = getGridColumn(gridSector.minLongitude)
            var x2 = getGridColumn(gridSector.maxLongitude)
            val y1 = getGridRow(gridSector.minLatitude)
            val y2 = getGridRow(gridSector.maxLatitude)
            // Adjust rectangle to include special zones
            if (y1 <= 17 && y2 >= 17 && x2 == 30) x2 = 31 // 32V Norway
            if (y1 <= 19 && y2 >= 19) { // X band
                if (x1 == 31) x1 = 30 // 31X
                if (x2 == 31) x2 = 32 // 33X
                if (x1 == 33) x1 = 32 // 33X
                if (x2 == 33) x2 = 34 // 35X
                if (x1 == 35) x1 = 34 // 35X
                if (x2 == 35) x2 = 36 // 37X
            }
            rectangle = Viewport(x1, y1, x2, y2) // Viewport is used as simple integer rectangle
        }
        return rectangle
    }

    private fun getGridColumn(longitude: Angle) = floor((longitude.inDegrees + 180) / 6.0).toInt().coerceAtMost(59)

    private fun getGridRow(latitude: Angle) = floor((latitude.inDegrees + 80) / 8.0).toInt().coerceAtMost(19)

    private fun getGridSector(row: Int, col: Int): Sector {
        val minLat = -80 + row * 8
        val maxLat = minLat + if (minLat != 72) 8 else 12
        var minLon = -180 + col * 6
        var maxLon = minLon + 6
        // Special sectors
        if (row == 17 && col == 30) // 31V
            maxLon -= 3 else if (row == 17 && col == 31) // 32V
            minLon -= 3 else if (row == 19 && col == 30) // 31X
            maxLon += 3 else if (row == 19 && col == 31) { // 32X does not exist
            minLon += 3
            maxLon -= 3
        } else if (row == 19 && col == 32) { // 33X
            minLon -= 3
            maxLon += 3
        } else if (row == 19 && col == 33) { // 34X does not exist
            minLon += 3
            maxLon -= 3
        } else if (row == 19 && col == 34) { // 35X
            minLon -= 3
            maxLon += 3
        } else if (row == 19 && col == 35) { // 36X does not exist
            minLon += 3
            maxLon -= 3
        } else if (row == 19 && col == 36) // 37X
            minLon -= 3
        return fromDegrees(minLat.toDouble(), minLon.toDouble(), (maxLat - minLat).toDouble(), (maxLon - minLon).toDouble())
    }

    fun isNorthNeighborInView(gz: MGRSGridZone, rc: RenderContext): Boolean {
        if (gz.isUPS) return true
        val row = getGridRow(gz.sector.centroidLatitude)
        val col = getGridColumn(gz.sector.centroidLongitude)
        val neighbor = if (row + 1 <= 19) gridZones[row + 1][col] else null
        return neighbor?.isInView(rc) == true
    }

    fun isEastNeighborInView(gz: MGRSGridZone, rc: RenderContext): Boolean {
        if (gz.isUPS) return true
        val row = getGridRow(gz.sector.centroidLatitude)
        val col = getGridColumn(gz.sector.centroidLongitude)
        val neighbor = if (col + 1 <= 59) gridZones[row][col + 1] else null
        return neighbor?.isInView(rc) == true
    }

    companion object {
        const val MGRS_OVERVIEW_RESOLUTION = 1e6
        const val MGRS_GRID_ZONE_RESOLUTION = 5e5

        /** Graticule for the MGRS overview.  */
        private const val GRATICULE_MGRS_OVERVIEW = "Graticule.MGRS.Overview"

        /** Graticule for the MGRS grid zone.  */
        private const val GRATICULE_MGRS_GRID_ZONE = "Graticule.MGRS.GridZone"
        private const val GRID_ZONE_MAX_ALTITUDE = 5e6
    }
}