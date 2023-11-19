package earth.worldwind.layer.graticule.utm

import earth.worldwind.geom.Angle.Companion.toDegrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.coords.Hemisphere
import earth.worldwind.geom.coords.MGRSCoord
import earth.worldwind.geom.coords.UTMCoord
import earth.worldwind.layer.graticule.AbstractGraticuleTile
import earth.worldwind.layer.graticule.GridElement
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_EAST
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_NORTH
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_SOUTH
import earth.worldwind.layer.graticule.utm.MGRSGraticuleLayer.Companion.MGRS_GRID_ZONE_RESOLUTION
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.PathType

/**
 * Represent a UTM zone / latitude band intersection
 */
class MGRSGridZone(layer: MGRSGraticuleLayer, sector: Sector) : AbstractGraticuleTile(layer, sector) {
    val isUPS = sector.maxLatitude.inDegrees > AbstractUTMGraticuleLayer.UTM_MAX_LATITUDE
            || sector.minLatitude.inDegrees < AbstractUTMGraticuleLayer.UTM_MIN_LATITUDE
    private var name: String
    private var hemisphere: Hemisphere
    private var zone: Int
    private var squares: List<UTMSquareZone>? = null
    override val layer get() = super.layer as MGRSGraticuleLayer

    init {
        val mgrs = MGRSCoord.fromLatLon(sector.centroidLatitude, sector.centroidLongitude)
        if (isUPS) {
            name = mgrs.toString().substring(2, 3)
            hemisphere = if (sector.minLatitude.inDegrees > 0) Hemisphere.N else Hemisphere.S
            zone = 0
        } else {
            name = mgrs.toString().substring(0, 3)
            val utm = UTMCoord.fromLatLon(sector.centroidLatitude, sector.centroidLongitude)
            hemisphere = utm.hemisphere
            zone = utm.zone
        }
    }

    override fun selectRenderables(rc: RenderContext) {
        super.selectRenderables(rc)
        val graticuleType = layer.getTypeFor(MGRS_GRID_ZONE_RESOLUTION)
        for (ge in gridElements) if (ge.isInView(rc)) {
            if (ge.type == TYPE_LINE_NORTH && layer.isNorthNeighborInView(this, rc)) continue
            if (ge.type == TYPE_LINE_EAST && layer.isEastNeighborInView(this, rc)) continue
            layer.addRenderable(ge.renderable, graticuleType)
        }
        if (rc.camera.position.altitude > SQUARE_MAX_ALTITUDE) return

        // Select 100km squares elements
        val squares = squares ?: (if (isUPS) createSquaresUPS() else createSquaresUTM()).also { squares = it }
        for (sz in squares) if (sz.isInView(rc)) sz.selectRenderables(rc) else sz.clearRenderables()
    }

    override fun clearRenderables() {
        super.clearRenderables()
        squares?.forEach { it.clearRenderables() }.also { squares = null }
    }

    override fun createRenderables() {
        super.createRenderables()
        val positions = mutableListOf(
            Position(sector.minLatitude, sector.minLongitude, 10e3),
            Position(sector.maxLatitude, sector.minLongitude, 10e3)
        )
        var polyline = layer.createLineRenderable(ArrayList(positions), PathType.LINEAR)
        var lineSector = Sector(
            sector.minLatitude, sector.maxLatitude, sector.minLongitude, sector.minLongitude
        )
        gridElements.add(GridElement(lineSector, polyline, GridElement.TYPE_LINE_WEST))
        if (!isUPS) {
            // right meridian segment
            positions.clear()
            positions.add(Position(sector.minLatitude, sector.maxLongitude, 10e3))
            positions.add(Position(sector.maxLatitude, sector.maxLongitude, 10e3))
            polyline = layer.createLineRenderable(ArrayList(positions), PathType.LINEAR)
            lineSector = Sector(
                sector.minLatitude, sector.maxLatitude, sector.maxLongitude, sector.maxLongitude
            )
            gridElements.add(GridElement(lineSector, polyline, TYPE_LINE_EAST))

            // bottom parallel segment
            positions.clear()
            positions.add(Position(sector.minLatitude, sector.minLongitude, 10e3))
            positions.add(Position(sector.minLatitude, sector.maxLongitude, 10e3))
            polyline = layer.createLineRenderable(ArrayList(positions), PathType.LINEAR)
            lineSector = Sector(
                sector.minLatitude, sector.minLatitude, sector.minLongitude, sector.maxLongitude
            )
            gridElements.add(GridElement(lineSector, polyline, TYPE_LINE_SOUTH))

            // top parallel segment
            positions.clear()
            positions.add(Position(sector.maxLatitude, sector.minLongitude, 10e3))
            positions.add(Position(sector.maxLatitude, sector.maxLongitude, 10e3))
            polyline = layer.createLineRenderable(ArrayList(positions), PathType.LINEAR)
            lineSector = Sector(
                sector.maxLatitude, sector.maxLatitude, sector.minLongitude, sector.maxLongitude
            )
            gridElements.add(GridElement(lineSector, polyline, TYPE_LINE_NORTH))
        }

        // Label
        val text = layer.createTextRenderable(
            Position(sector.centroidLatitude, sector.centroidLongitude, 0.0), name, 10e6
        )
        gridElements.add(GridElement(sector, text, GridElement.TYPE_GRIDZONE_LABEL))
    }

    private fun createSquaresUTM(): List<UTMSquareZone> {
        // Find grid zone easting and northing boundaries
        var utm = UTMCoord.fromLatLon(sector.minLatitude, sector.centroidLongitude)
        val minNorthing = utm.northing
        utm = UTMCoord.fromLatLon(sector.maxLatitude, sector.centroidLongitude)
        var maxNorthing = utm.northing
        maxNorthing = if (maxNorthing == 0.0) 10e6 else maxNorthing
        utm = UTMCoord.fromLatLon(sector.minLatitude, sector.minLongitude)
        var minEasting = utm.easting
        utm = UTMCoord.fromLatLon(sector.maxLatitude, sector.minLongitude)
        minEasting = utm.easting.coerceAtMost(minEasting)
        var maxEasting = 1e6 - minEasting

        // Compensate for some distorted zones
        if (name == "32V") maxNorthing += 20e3 // catch KS and LS in 32V
        if (name == "31X") maxEasting += ONEHT // catch GA and GV in 31X

        // Create squares
        return layer.createSquaresGrid(zone, hemisphere, sector, minEasting, maxEasting, minNorthing, maxNorthing).also {
            for (square in it) setSquareName(square)
        }
    }

    private fun createSquaresUPS(): List<UTMSquareZone> {
        val minEasting: Double
        val maxEasting: Double
        val minNorthing: Double
        val maxNorthing: Double
        if (Hemisphere.N == hemisphere) {
            minNorthing = TWOMIL - ONEHT * 7
            maxNorthing = TWOMIL + ONEHT * 7
            minEasting = if (name == "Y") TWOMIL - ONEHT * 7 else TWOMIL
            maxEasting = if (name == "Y") TWOMIL else TWOMIL + ONEHT * 7
        } else {
            minNorthing = TWOMIL - ONEHT * 12
            maxNorthing = TWOMIL + ONEHT * 12
            minEasting = if (name == "A") TWOMIL - ONEHT * 12 else TWOMIL
            maxEasting = if (name == "A") TWOMIL else TWOMIL + ONEHT * 12
        }

        // Create squares
        return layer.createSquaresGrid(zone, hemisphere, sector, minEasting, maxEasting, minNorthing, maxNorthing).also {
            for (square in it) setSquareName(square)
        }
    }

    private fun setSquareName(sz: UTMSquareZone) {
        // Find out MGRS 100Km square name
        val tenMeterDegree = toDegrees(10.0 / 6378137.0)
        var mgrs: MGRSCoord? = null
        when {
            sz.isPositionInside(Position(sz.centroid.latitude, sz.centroid.longitude, 0.0)) ->
                mgrs = MGRSCoord.fromLatLon(sz.centroid.latitude, sz.centroid.longitude)
            sz.isPositionInside(sz.sw) -> mgrs = MGRSCoord.fromLatLon(
                sz.sw.latitude.plusDegrees(tenMeterDegree).clampLatitude(),
                sz.sw.longitude.plusDegrees(tenMeterDegree).clampLongitude()
            )
            sz.isPositionInside(sz.se) -> mgrs = MGRSCoord.fromLatLon(
                sz.se.latitude.plusDegrees(tenMeterDegree).clampLatitude(),
                sz.se.longitude.minusDegrees(tenMeterDegree).clampLongitude()
            )
            sz.isPositionInside(sz.nw) -> mgrs = MGRSCoord.fromLatLon(
                sz.nw.latitude.minusDegrees(tenMeterDegree).clampLatitude(),
                sz.nw.longitude.plusDegrees(tenMeterDegree).clampLongitude())
            sz.isPositionInside(sz.ne) -> mgrs = MGRSCoord.fromLatLon(
                sz.ne.latitude.minusDegrees(tenMeterDegree).clampLatitude(),
                sz.ne.longitude.minusDegrees(tenMeterDegree).clampLongitude()
            )
        }
        // Set square zone name
        if (mgrs != null) sz.name = mgrs.toString().substring(3, 5)
    }

    companion object {
        private const val ONEHT = 100e3
        private const val TWOMIL = 2e6
        private const val SQUARE_MAX_ALTITUDE = 3000e3
    }
}