package earth.worldwind.layer.graticule

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.coords.Hemisphere
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_GRIDZONE_LABEL
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_EAST
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_NORTH
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_SOUTH
import earth.worldwind.layer.graticule.GridElement.Companion.TYPE_LINE_WEST
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.PathType
import kotlin.math.cos

/**
 * Represent a 100km square zone inside an UTM zone.
 */
class UTMSquareZone(
    layer: AbstractUTMGraticuleLayer, UTMZone: Int, hemisphere: Hemisphere, UTMZoneSector: Sector,
    SWEasting: Double, SWNorthing: Double, size: Double
): UTMSquareSector(layer, UTMZone, hemisphere, UTMZoneSector, SWEasting, SWNorthing, size) {
    var name: String? = null
    var northNeighbor: UTMSquareZone? = null
    var eastNeighbor: UTMSquareZone? = null
    private var squareGrid: UTMSquareGrid? = null

    override fun isInView(rc: RenderContext) = super.isInView(rc) && getSizeInPixels(rc) > MIN_CELL_SIZE_PIXELS

    override fun selectRenderables(rc: RenderContext) {
        super.selectRenderables(rc)
        val drawMetricLabels = getSizeInPixels(rc) > MIN_CELL_SIZE_PIXELS * 2
        val graticuleType = layer.getTypeFor(size)
        for (ge in gridElements!!) {
            if (ge.isInView(rc)) {
                if (ge.type == TYPE_LINE_NORTH && isNorthNeighborInView(rc)) continue
                if (ge.type == TYPE_LINE_EAST && isEastNeighborInView(rc)) continue
                if (drawMetricLabels) layer.computeMetricScaleExtremes(
                    UTMZone, hemisphere, ge, size * 10
                )
                layer.addRenderable(ge.renderable, graticuleType)
            }
        }
        if (getSizeInPixels(rc) <= MIN_CELL_SIZE_PIXELS * 2) return

        // Select grid renderables
        val squareGrid = squareGrid ?: UTMSquareGrid(layer, UTMZone, hemisphere, UTMZoneSector, SWEasting, SWNorthing, size).also { squareGrid = it }
        if (squareGrid.isInView(rc)) squareGrid.selectRenderables(rc) else squareGrid.clearRenderables()
    }

    private fun isNorthNeighborInView(rc: RenderContext) = northNeighbor?.isInView(rc) == true

    private fun isEastNeighborInView(rc: RenderContext) = eastNeighbor?.isInView(rc) == true

    override fun clearRenderables() {
        super.clearRenderables()
        squareGrid?.clearRenderables()
        squareGrid = null
    }

    override fun createRenderables() {
        super.createRenderables()
        val positions = mutableListOf<Position>()

        // left segment
        if (isTruncated) {
            layer.computeTruncatedSegment(sw, nw, UTMZoneSector, positions)
        } else {
            positions.add(sw)
            positions.add(nw)
        }
        if (positions.size > 0) {
            val p1 = positions[0]
            val p2 = positions[1]
            val polyline = layer.createLineRenderable(ArrayList(positions), PathType.GREAT_CIRCLE)
            val lineSector = boundingSector(p1, p2)
            gridElements!!.add(
                GridElement(lineSector, polyline, TYPE_LINE_WEST, SWEasting.degrees)
            )
        }

        // right segment
        positions.clear()
        if (isTruncated) {
            layer.computeTruncatedSegment(se, ne, UTMZoneSector, positions)
        } else {
            positions.add(se)
            positions.add(ne)
        }
        if (positions.size > 0) {
            val p1 = positions[0]
            val p2 = positions[1]
            val polyline = layer.createLineRenderable(ArrayList(positions), PathType.GREAT_CIRCLE)
            val lineSector = boundingSector(p1, p2)
            gridElements!!.add(GridElement(lineSector, polyline, TYPE_LINE_EAST, (SWEasting + size).degrees))
        }

        // bottom segment
        positions.clear()
        if (isTruncated) {
            layer.computeTruncatedSegment(sw, se, UTMZoneSector, positions)
        } else {
            positions.add(sw)
            positions.add(se)
        }
        if (positions.size > 0) {
            val p1 = positions[0]
            val p2 = positions[1]
            val polyline = layer.createLineRenderable(ArrayList(positions), PathType.GREAT_CIRCLE)
            val lineSector = boundingSector(p1, p2)
            gridElements!!.add(GridElement(lineSector, polyline, TYPE_LINE_SOUTH, SWNorthing.degrees))
        }

        // top segment
        positions.clear()
        if (isTruncated) {
            layer.computeTruncatedSegment(nw, ne, UTMZoneSector, positions)
        } else {
            positions.add(nw)
            positions.add(ne)
        }
        if (positions.size > 0) {
            val p1 = positions[0]
            val p2 = positions[1]
            val polyline = layer.createLineRenderable(ArrayList(positions), PathType.GREAT_CIRCLE)
            val lineSector = boundingSector(p1, p2)
            gridElements!!.add(GridElement(lineSector, polyline, TYPE_LINE_NORTH, (SWNorthing + size).degrees))
        }

        // Label
        if (name != null) {
            // Only add a label to squares above some dimension
            if (boundingSector.deltaLongitude.inDegrees * cos(centroid.latitude.inRadians) > .2
                && boundingSector.deltaLatitude.inDegrees > .2
            ) {
                val labelPos = if (UTMZone != 0) centroid // Not at poles
                else if (isPositionInside(Position(squareCenter.latitude, squareCenter.longitude, 0.0))) squareCenter
                else if (squareCenter.latitude.inDegrees <= UTMZoneSector.maxLatitude.inDegrees
                    && squareCenter.latitude.inDegrees >= UTMZoneSector.minLatitude.inDegrees) centroid
                else null
                if (labelPos != null) {
                    val text = layer.createTextRenderable(
                        Position(labelPos.latitude, labelPos.longitude, 0.0), name!!, size * 10
                    )
                    gridElements!!.add(GridElement(boundingSector, text, TYPE_GRIDZONE_LABEL))
                }
            }
        }
    }
}