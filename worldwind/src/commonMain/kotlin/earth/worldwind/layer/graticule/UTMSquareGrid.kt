package earth.worldwind.layer.graticule

import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.coords.Hemisphere
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_EASTING
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_NORTHING
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.PathType

/**
 * Represent a square 10x10 grid and recursive tree in easting/northing coordinates
 */
internal class UTMSquareGrid(
    layer: AbstractUTMGraticuleLayer, UTMZone: Int, hemisphere: Hemisphere, UTMZoneSector: Sector,
    SWEasting: Double, SWNorthing: Double, size: Double
): UTMSquareSector(layer, UTMZone, hemisphere, UTMZoneSector, SWEasting, SWNorthing, size) {
    private var subGrids: MutableList<UTMSquareGrid>? = null

    override fun isInView(rc: RenderContext): Boolean {
        return super.isInView(rc) && getSizeInPixels(rc) > MIN_CELL_SIZE_PIXELS * 4
    }

    override fun selectRenderables(rc: RenderContext) {
        super.selectRenderables(rc)
        val drawMetricLabels = getSizeInPixels(rc) > MIN_CELL_SIZE_PIXELS * 4 * 1.7
        val graticuleType = layer.getTypeFor(size / 10)
        for (ge in gridElements!!) {
            if (ge.isInView(rc)) {
                if (drawMetricLabels) layer.computeMetricScaleExtremes(UTMZone, hemisphere, ge, size)
                layer.addRenderable(ge.renderable, graticuleType)
            }
        }
        if (getSizeInPixels(rc) <= MIN_CELL_SIZE_PIXELS * 4 * 2) return

        // Select sub grids renderables
        subGrids ?: createSubGrids()
        for (sg in subGrids!!) if (sg.isInView(rc)) sg.selectRenderables(rc) else sg.clearRenderables()
    }

    override fun clearRenderables() {
        super.clearRenderables()
        subGrids?.run {
            for (sg in this) sg.clearRenderables()
            clear()
        }.also { subGrids = null }
    }

    private fun createSubGrids() {
        subGrids = mutableListOf()
        val gridStep = size / 10
        for (i in 0..9) {
            val easting = SWEasting + gridStep * i
            for (j in 0..9) {
                val northing = SWNorthing + gridStep * j
                val sg = UTMSquareGrid(layer, UTMZone, hemisphere, UTMZoneSector, easting, northing, gridStep)
                if (!sg.isOutsideGridZone) subGrids!!.add(sg)
            }
        }
    }

    override fun createRenderables() {
        super.createRenderables()
        val gridStep = size / 10
        val positions = mutableListOf<Position>()

        // South-North lines
        for (i in 1..9) {
            val easting = SWEasting + gridStep * i
            positions.clear()
            var p1 = layer.computePosition(UTMZone, hemisphere, easting, SWNorthing)
            var p2 = layer.computePosition(UTMZone, hemisphere, easting, SWNorthing + size)
            if (isTruncated) {
                layer.computeTruncatedSegment(p1, p2, UTMZoneSector, positions)
            } else {
                positions.add(p1)
                positions.add(p2)
            }
            if (positions.size > 0) {
                p1 = positions[0]
                p2 = positions[1]
                val polyline = layer.createLineRenderable(ArrayList(positions), PathType.GREAT_CIRCLE)
                val lineSector = boundingSector(p1, p2)
                gridElements!!.add(
                    GridElement(lineSector, polyline, TYPE_LINE_EASTING, fromDegrees(easting))
                )
            }
        }
        // West-East lines
        for (i in 1..9) {
            val northing = SWNorthing + gridStep * i
            positions.clear()
            var p1 = layer.computePosition(UTMZone, hemisphere, SWEasting, northing)
            var p2 = layer.computePosition(UTMZone, hemisphere, SWEasting + size, northing)
            if (isTruncated) {
                layer.computeTruncatedSegment(p1, p2, UTMZoneSector, positions)
            } else {
                positions.add(p1)
                positions.add(p2)
            }
            if (positions.size > 0) {
                p1 = positions[0]
                p2 = positions[1]
                val polyline = layer.createLineRenderable(ArrayList(positions), PathType.GREAT_CIRCLE)
                val lineSector = boundingSector(p1, p2)
                gridElements!!.add(GridElement(lineSector, polyline, TYPE_LINE_NORTHING, fromDegrees(northing)))
            }
        }
    }
}