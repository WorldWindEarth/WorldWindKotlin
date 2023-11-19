package earth.worldwind.layer.graticule.utm

import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.coords.Hemisphere
import earth.worldwind.geom.coords.UTMCoord
import earth.worldwind.layer.graticule.AbstractGraticuleTile
import earth.worldwind.layer.graticule.GridElement
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_GRIDZONE_LABEL
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE
import earth.worldwind.layer.graticule.utm.AbstractUTMGraticuleLayer.Companion.UTM_MAX_LATITUDE
import earth.worldwind.layer.graticule.utm.AbstractUTMGraticuleLayer.Companion.UTM_MIN_LATITUDE
import earth.worldwind.layer.graticule.utm.UTMGraticuleLayer.Companion.UTM_ZONE_RESOLUTION
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.PathType

internal class UTMGraticuleTile(layer: UTMGraticuleLayer, sector: Sector, private val zone: Int): AbstractGraticuleTile(layer, sector) {
    private val hemisphere = if (sector.centroidLatitude.inDegrees > 0) Hemisphere.N else Hemisphere.S
    private var squares: List<UTMSquareZone>? = null
    override val layer get() = super.layer as UTMGraticuleLayer

    override fun selectRenderables(rc: RenderContext) {
        super.selectRenderables(rc)

        // Select tile grid elements
        val graticuleType = layer.getTypeFor(UTM_ZONE_RESOLUTION)
        for (ge in gridElements) if (ge.isInView(rc)) layer.addRenderable(ge.renderable, graticuleType)
        if (getSizeInPixels(rc) / 10 < MIN_CELL_SIZE_PIXELS * 2) return

        // Select child elements
        val squares = squares ?: createSquares().also { squares = it }
        for (sz in squares) if (sz.isInView(rc)) sz.selectRenderables(rc) else sz.clearRenderables()
    }

    override fun clearRenderables() {
        super.clearRenderables()
        squares?.forEach { it.clearRenderables() }.also { squares = null }
    }

    private fun createSquares(): List<UTMSquareZone> {
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
        val maxEasting = 1e6 - minEasting

        // Create squares
        return layer.createSquaresGrid(zone, hemisphere, sector, minEasting, maxEasting, minNorthing, maxNorthing)
    }

    override fun createRenderables() {
        super.createRenderables()
        val positions = mutableListOf(
            Position(sector.minLatitude, sector.minLongitude, 0.0),
            Position(sector.maxLatitude, sector.minLongitude, 0.0)
        )
        var polyline = layer.createLineRenderable(positions.toList(), PathType.LINEAR)
        var lineSector = Sector(sector.minLatitude, sector.maxLatitude, sector.minLongitude, sector.minLongitude)
        gridElements.add(GridElement(lineSector, polyline, TYPE_LINE, sector.minLongitude))

        // Generate south parallel at the South Pole and equator
        if (sector.minLatitude.inDegrees == UTM_MIN_LATITUDE || sector.minLatitude.inDegrees == 0.0) {
            positions.clear()
            positions.add(Position(sector.minLatitude, sector.minLongitude, 0.0))
            positions.add(Position(sector.minLatitude, sector.maxLongitude, 0.0))
            polyline = layer.createLineRenderable(ArrayList(positions), PathType.LINEAR)
            lineSector = Sector(sector.minLatitude, sector.minLatitude, sector.minLongitude, sector.maxLongitude)
            gridElements.add(GridElement(lineSector, polyline, TYPE_LINE, sector.minLatitude))
        }

        // Generate north parallel at North Pole
        if (sector.maxLatitude.inDegrees == UTM_MAX_LATITUDE) {
            positions.clear()
            positions.add(Position(sector.maxLatitude, sector.minLongitude, 0.0))
            positions.add(Position(sector.maxLatitude, sector.maxLongitude, 0.0))
            polyline = layer.createLineRenderable(ArrayList(positions), PathType.LINEAR)
            lineSector = Sector(sector.maxLatitude, sector.maxLatitude, sector.minLongitude, sector.maxLongitude)
            gridElements.add(GridElement(lineSector, polyline, TYPE_LINE, sector.maxLatitude))
        }

        // Add label
        if (hasLabel()) {
            val text = layer.createTextRenderable(
                Position(sector.centroidLatitude, sector.centroidLongitude, 0.0),
                zone.toString() + hemisphere, 10e6
            )
            gridElements.add(GridElement(sector, text, TYPE_GRIDZONE_LABEL))
        }
    }

    private fun hasLabel(): Boolean {
        // Has label if it contains hemisphere mid latitude
        val southLat = UTM_MIN_LATITUDE / 2.0
        val southLabel = sector.minLatitude.inDegrees < southLat && southLat <= sector.maxLatitude.inDegrees
        val northLat = UTM_MAX_LATITUDE / 2.0
        val northLabel = (sector.minLatitude.inDegrees < northLat && northLat <= sector.maxLatitude.inDegrees)
        return southLabel || northLabel
    }

    companion object {
        private const val MIN_CELL_SIZE_PIXELS = 40 // TODO: make settable
    }
}