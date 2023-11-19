package earth.worldwind.layer.graticule.gk

import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.layer.graticule.AbstractGraticuleTile
import earth.worldwind.layer.graticule.GridElement
import earth.worldwind.layer.graticule.gk.GKGraticuleLayer.Companion.GK_METRIC_GRID_1000
import earth.worldwind.layer.graticule.gk.GKGraticuleLayer.Companion.GK_METRIC_GRID_2000
import earth.worldwind.layer.graticule.gk.GKGraticuleLayer.Companion.GRATICULE_GK_10_000
import earth.worldwind.layer.graticule.gk.GKGraticuleLayer.Companion.GRATICULE_GK_25_000
import earth.worldwind.layer.graticule.gk.GKGraticuleLayer.Companion.GRATICULE_GK_500_000
import earth.worldwind.layer.graticule.gk.GKGraticuleLayer.Companion.GRATICULE_GK_50_000
import earth.worldwind.layer.graticule.gk.GKLayerHelper.getNameByCoord
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.PathType

private const val POINTS_IN_LINE = 3

class GKGraticuleTile(
    layer: GKGraticuleLayer, private val gkSector: Sector, private val tileType: String, previousName: String = "",
    private val countInParent: Int = 0, private val childNumber: Int = 0
) : AbstractGraticuleTile(layer, layer.getUnprojectedSector(gkSector)) {
    private val name = getNameByCoord(gkSector.centroidLatitude, gkSector.centroidLongitude, tileType, previousName)
    private var subTiles: List<GKGraticuleTile>? = null
    private var metricSquares: GKMetricGrid? = null
    private var renderWithNeighbors = true

    override val layer get() = super.layer as GKGraticuleLayer

    override fun selectRenderables(rc: RenderContext) {
        // TODO Remove workaround and add logic for 30th zone after fix of Path drawing at -179 to +179 longitude
        if (GKLayerHelper.getZone(gkSector.centroidLongitude) == 30) return

        super.selectRenderables(rc)
        val distanceToTile = nearestPoint(rc).distanceTo(rc.cameraPoint)
        val appropriateType = layer.getTypeFor(distanceToTile)
        enableRenderingForChildTile(appropriateType, distanceToTile)
        for (ge in gridElements) {
            if (ge.type == GridElement.TYPE_GRIDZONE_LABEL) renderLabel(appropriateType, rc, ge)
            else if (ge.isInView(rc)) layer.addRenderable(ge.renderable, tileType)
        }
        renderMetricGraticule()
        if (shouldCreateSubTile(distanceToTile)) {
            val subTiles = subTiles ?: createSubTiles().also { subTiles = it }
            for (subTile in subTiles) {
                if (subTile.isInView(rc)) subTile.selectRenderables(rc) //else subTile.clearRenderables()
            }
        }
    }

    private fun renderLabel(appropriateType: String, rc: RenderContext, label: GridElement) {
        if (shouldRenderLabel(appropriateType, rc)) layer.addRenderable(label.renderable, tileType)
    }

    private fun shouldRenderLabel(type: String, rc: RenderContext) =
        (((type == tileType || renderWithNeighbors) && shouldRenderSmallScale()) || shouldRenderMinimalScale()) && isInView(rc)

    private fun shouldRenderMinimalScale() = !layer.showDetailedSheets && tileType == GRATICULE_GK_50_000

    private fun shouldRenderSmallScale() =
        !(!layer.showDetailedSheets && (tileType == GRATICULE_GK_25_000 || tileType == GRATICULE_GK_10_000))

    private fun renderMetricGraticule() {
        if (tileType == GRATICULE_GK_25_000) {
            layer.setMetricLabelScale(2000)
            metricSquares?.selectRenderables(GK_METRIC_GRID_2000)
        } else if (tileType == GRATICULE_GK_10_000) {
            layer.setMetricLabelScale(1000)
            metricSquares?.selectRenderables(GK_METRIC_GRID_1000)
        }
    }

    private fun enableRenderingForChildTile(type: String, distanceToTile: Double) {
        val typeWithBiggerScale = getTypeWithBiggerScale()
        if (type == typeWithBiggerScale) {
            renderWithNeighbors = false
            subTiles?.forEach { it.renderWithNeighbors = true }
        } else if ( distanceToTile < layer.getDistanceFor(typeWithBiggerScale)) {
            renderWithNeighbors =  false
        }
    }

    private fun shouldCreateSubTile(distanceToTile: Double) =
        distanceToTile <= layer.getDistanceFor(tileType) && tileType != GRATICULE_GK_10_000

    override fun clearRenderables() {
        super.clearRenderables()
        metricSquares?.clearRenderables()
        subTiles?.forEach { it.clearRenderables() }.also { subTiles = null }
    }

    private fun createSubTiles(): List<GKGraticuleTile> {
        val newType = getTypeWithBiggerScale()
        val div = if (tileType == GRATICULE_GK_500_000) 3 else 2
        var count = 1
        return subdivide(div, gkSector).map { GKGraticuleTile(layer, it, newType, name, div*div, count++) }
    }

    private fun getTypeWithBiggerScale() = layer.getTypeFor(layer.getDistanceFor(tileType) * 0.8)

    override fun createRenderables() {
        super.createRenderables()

        if(!name.startsWith("Z") && !name.startsWith("SZ")){
            // TODO Fix problem with Z zone and add logic for maps under 60 parallels
            // TODO Fix problem related with transformation near the end of graticule zones
            if (shouldRenderSmallScale()) {
                generateMeridiansAndParallels()
                createLabels()
            }
            createMetricGraticule()
        }
    }

    private fun generateMeridiansAndParallels() {
        if (shouldGenerateMeridian()) generateWestMeridian()
        if (shouldGenerateParallel()) generateNorthParallel()
    }

    private fun shouldGenerateMeridian() = countInParent == 0 ||
            countInParent == 4 && (childNumber == 2 || childNumber == 4) ||
            countInParent == 9 && (childNumber == 2 || childNumber == 3 ||
            childNumber == 5 || childNumber == 6 || childNumber == 8 || childNumber == 9)

    private fun shouldGenerateParallel() =
        countInParent == 0 || countInParent == 4 && childNumber >= 3 || countInParent == 9 && childNumber >= 4

    private fun generateWestMeridian() {
        val minLon = gkSector.minLongitude
        val minLat = gkSector.minLatitude
        val latStep = gkSector.deltaLatitude.inDegrees / POINTS_IN_LINE
        val positions = mutableListOf<Position>()
        for (i in 0..POINTS_IN_LINE) positions.add(
            layer.transformToWGS(Position(minLat.plusDegrees(i * latStep), minLon, 0.0)))
        val westLine = layer.createLineRenderable(positions, PathType.LINEAR)
        gridElements.add(GridElement(sector, westLine, GridElement.TYPE_LINE_WEST, minLon))
    }

    private fun generateNorthParallel() {
        val minLon = gkSector.minLongitude
        val minLat = gkSector.minLatitude
        val positions = mutableListOf<Position>()
        val lonStep = gkSector.deltaLongitude.inDegrees / POINTS_IN_LINE
        for (i in 0..POINTS_IN_LINE) positions.add(
            layer.transformToWGS(Position(minLat, minLon.plusDegrees(i * lonStep), 0.0)))
        val northLine = layer.createLineRenderable(positions, PathType.LINEAR)
        gridElements.add(GridElement(sector, northLine, GridElement.TYPE_LINE_NORTH, minLat))
    }

    private fun createLabels() {
        val labelPos = Position(sector.centroidLatitude, sector.centroidLongitude, 0.0)
        val text = layer.createTextRenderable(labelPos, name, layer.getDistanceFor(tileType))
        text.attributes.isOutlineEnabled = false
        gridElements.add(GridElement(sector, text, GridElement.TYPE_GRIDZONE_LABEL))
    }

    private fun createMetricGraticule() {
        when (tileType) {
            GRATICULE_GK_25_000 -> {
                val squares = metricSquares ?: GKMetricGrid(layer, sector, gkSector, 2000.0).also { metricSquares = it }
                squares.createRenderables()
            }
            GRATICULE_GK_10_000 -> {
                val squares = metricSquares ?: GKMetricGrid(layer, sector, gkSector, 1000.0).also { metricSquares = it }
                squares.createRenderables()
            }
        }
    }
}
