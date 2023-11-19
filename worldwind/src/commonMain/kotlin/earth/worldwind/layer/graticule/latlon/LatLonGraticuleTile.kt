package earth.worldwind.layer.graticule.latlon

import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.layer.graticule.AbstractGraticuleTile
import earth.worldwind.layer.graticule.GridElement
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LATITUDE_LABEL
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_NORTH
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_SOUTH
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_WEST
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LONGITUDE_LABEL
import earth.worldwind.layer.graticule.latlon.AbstractLatLonGraticuleLayer.AngleFormat
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.PathType

internal class LatLonGraticuleTile(
    layer: LatLonGraticuleLayer, sector: Sector, private val divisions: Int, private val level: Int
) : AbstractGraticuleTile(layer, sector) {
    private var subTiles: MutableList<LatLonGraticuleTile>? = null
    override val layer get() = super.layer as LatLonGraticuleLayer

    override fun isInView(rc: RenderContext) =
        super.isInView(rc) && (level == 0 || getSizeInPixels(rc) / divisions >= MIN_CELL_SIZE_PIXELS)

    override fun selectRenderables(rc: RenderContext) {
        super.selectRenderables(rc)
        val labelOffset = layer.computeLabelOffset(rc)
        var graticuleType = layer.getTypeFor(sector.deltaLatitude.inDegrees)
        if (level == 0) {
            for (ge in gridElements!!) {
                if (ge.isInView(rc)) {
                    // Add level zero bounding lines and labels
                    if (ge.type == TYPE_LINE_SOUTH || ge.type == TYPE_LINE_NORTH || ge.type == TYPE_LINE_WEST) {
                        layer.addRenderable(ge.renderable, graticuleType)
                        val labelType = if (ge.type == TYPE_LINE_SOUTH || ge.type == TYPE_LINE_NORTH)
                            TYPE_LATITUDE_LABEL else TYPE_LONGITUDE_LABEL
                        layer.addLabel(ge.value, labelType, graticuleType, sector.deltaLatitude.inDegrees, labelOffset)
                    }
                }
            }
            if (getSizeInPixels(rc) / divisions < MIN_CELL_SIZE_PIXELS) return
        }

        // Select tile grid elements
        val resolution = sector.deltaLatitude.inDegrees / divisions
        graticuleType = layer.getTypeFor(resolution)
        for (ge in gridElements!!) {
            if (ge.isInView(rc)) {
                if (ge.type == TYPE_LINE) {
                    layer.addRenderable(ge.renderable, graticuleType)
                    val labelType = if (ge.sector.deltaLatitude.inDegrees < 1E-14) TYPE_LATITUDE_LABEL else TYPE_LONGITUDE_LABEL
                    layer.addLabel(ge.value, labelType, graticuleType, resolution, labelOffset)
                }
            }
        }
        if (getSizeInPixels(rc) / divisions < MIN_CELL_SIZE_PIXELS * 2) return

        // Select child elements
        subTiles ?: createSubTiles()
        for (gt in subTiles!!) if (gt.isInView(rc)) gt.selectRenderables(rc) else gt.clearRenderables()
    }

    override fun clearRenderables() {
        super.clearRenderables()
        subTiles?.run {
            for (gt in this) gt.clearRenderables()
            clear()
        }.also { subTiles = null }
    }

    private fun createSubTiles() {
        subTiles = mutableListOf()
        val sectors = subdivide(divisions)
        var subDivisions = 10
        if ((layer.angleFormat == AngleFormat.DMS || layer.angleFormat == AngleFormat.DM) && (level == 0 || level == 2))
            subDivisions = 6
        for (s in sectors) subTiles!!.add(LatLonGraticuleTile(layer, s, subDivisions, level + 1))
    }

    override fun createRenderables() {
        super.createRenderables()
        val step = sector.deltaLatitude.inDegrees / divisions

        // Generate meridians with labels
        var lon = sector.minLongitude.plusDegrees(if (level == 0) 0.0 else step)
        while (lon.inDegrees < sector.maxLongitude.inDegrees - step / 2) {
            // Meridian
            val positions = listOf(
                Position(sector.minLatitude, lon, 0.0), Position(sector.maxLatitude, lon, 0.0)
            )
            val line = layer.createLineRenderable(positions, PathType.LINEAR)
            val lineSector = Sector(sector.minLatitude, sector.maxLatitude, lon, lon)
            val lineType = if (lon == sector.minLongitude) TYPE_LINE_WEST else TYPE_LINE
            gridElements!!.add(GridElement(lineSector, line, lineType, lon))

            // Increase longitude
            lon = lon.plusDegrees(step)
        }

        // Generate parallels
        var lat = sector.minLatitude.plusDegrees(if (level == 0) 0.0 else step)
        while (lat.inDegrees < sector.maxLatitude.inDegrees - step / 2) {
            val positions = listOf(
                Position(lat, sector.minLongitude, 0.0), Position(lat, sector.maxLongitude, 0.0)
            )
            val line = layer.createLineRenderable(positions, PathType.LINEAR)
            val lineSector = Sector(lat, lat, sector.minLongitude, sector.maxLongitude)
            val lineType = if (lat == sector.minLatitude) TYPE_LINE_SOUTH else TYPE_LINE
            gridElements!!.add(GridElement(lineSector, line, lineType, lat))

            // Increase latitude
            lat = lat.plusDegrees(step)
        }

        // Draw and label a parallel at the top of the graticule. The line is apparent only on 2D globes.
        if (sector.maxLatitude == POS90) {
            val positions = listOf(
                Position(POS90, sector.minLongitude, 0.0), Position(POS90, sector.maxLongitude, 0.0)
            )
            val line = layer.createLineRenderable(positions, PathType.LINEAR)
            val lineSector = Sector(POS90, POS90, sector.minLongitude, sector.maxLongitude)
            gridElements!!.add(GridElement(lineSector, line, TYPE_LINE_NORTH, POS90))
        }
    }

    companion object {
        private const val MIN_CELL_SIZE_PIXELS = 40 // TODO: make settable
    }
}