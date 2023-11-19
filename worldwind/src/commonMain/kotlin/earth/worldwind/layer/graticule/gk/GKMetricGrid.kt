package earth.worldwind.layer.graticule.gk

import earth.worldwind.geom.Offset
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.coords.GKCoord
import earth.worldwind.layer.graticule.AbstractGraticuleTile
import earth.worldwind.layer.graticule.GridElement
import earth.worldwind.layer.graticule.gk.GKGraticuleLayer.Companion.GK_METRIC_GRID_1000
import earth.worldwind.layer.graticule.gk.GKGraticuleLayer.Companion.GK_METRIC_GRID_2000
import earth.worldwind.layer.graticule.gk.GKMetricLabels.Companion.LABEL_SCALE_TYPE
import earth.worldwind.layer.graticule.gk.GKMetricLabels.Companion.LABEL_TYPE_KEY
import earth.worldwind.layer.graticule.gk.GKMetricLabels.Companion.LABEL_TYPE_X_VALUE
import earth.worldwind.layer.graticule.gk.GKMetricLabels.Companion.LABEL_TYPE_Y_VALUE
import earth.worldwind.layer.graticule.gk.GKMetricLabels.Companion.LABEL_X_KEY
import earth.worldwind.layer.graticule.gk.GKMetricLabels.Companion.LABEL_Y_KEY
import earth.worldwind.shape.Label
import earth.worldwind.shape.PathType
import kotlin.math.abs

private const val SMALL_VALUE_TO_BEE_IN_ZONE = 0.000000000001

class GKMetricGrid(
    layer: GKGraticuleLayer, sector: Sector, gkSector: Sector, private val scale: Double
): AbstractGraticuleTile(layer, sector) {
    private val zone = GKLayerHelper.getZone(gkSector.centroidLongitude)
    private var zoneExtremes = ZoneExtremes(
        GKCoord.fromLatLon(gkSector.minLatitude, gkSector.minLongitude, zone),
        GKCoord.fromLatLon(gkSector.minLatitude, if (zone != 60) gkSector.maxLongitude else gkSector.maxLongitude.minusDegrees(SMALL_VALUE_TO_BEE_IN_ZONE), zone),
        GKCoord.fromLatLon(gkSector.maxLatitude, if (zone != 60) gkSector.maxLongitude else gkSector.maxLongitude.minusDegrees(SMALL_VALUE_TO_BEE_IN_ZONE), zone),
        GKCoord.fromLatLon(gkSector.maxLatitude, gkSector.minLongitude, zone)
    )

    override val layer get() = super.layer as GKGraticuleLayer

    fun selectRenderables(lineType :String) = gridElements.forEach {
        if (it.type == lineType) {
            layer.addRenderable(it.renderable, lineType)
        } else if (it.type == METRIC_LABEL && it.renderable is Label) {
            layer.addMetricLabel(it.renderable)
        }
    }

    override fun createRenderables() {
        super.createRenderables()
        val xMinZone = minOf(zoneExtremes.minXMinY.x, zoneExtremes.minXMaxY.x)
        val xMaxZone = maxOf(zoneExtremes.maxXMinY.x, zoneExtremes.maxXMaxY.x)
        val yMinZone = maxOf(zoneExtremes.minXMinY.y, zoneExtremes.maxXMinY.y)
        val yMaxZone = minOf(zoneExtremes.maxXMaxY.y, zoneExtremes.minXMaxY.y)

        val firstRow = (xMinZone / scale).toInt()
        val lastRow = (xMaxZone / scale).toInt()
        val firstCol = (yMinZone / scale).toInt()
        val lastCol = (yMaxZone / scale).toInt()

        createLinesAndLabels(firstRow, lastRow, firstCol, lastCol)
        createYLineThatIntersectCorners(firstCol, firstRow, lastRow, lastCol)
    }

    private fun createLinesAndLabels(firstRow: Int, lastRow: Int, firstCol: Int, lastCol: Int) {
        for (row in firstRow..lastRow) {
            val startX = row.toDouble() * scale
            val nextX = row.toDouble() * scale + scale

            for (col in firstCol..lastCol) {
                val startY = col * scale
                val startPoint = getWGSPositionFromXY(startX, startY)

                if (row != firstRow) {
                    // Creat x line
                    val nextY = col * scale + scale
                    val movedByYPoint = getWGSPositionFromXY(startX, nextY)
                    when (col) {
                        firstCol -> {
                            // Create correct intersection with west map
                            val intersectPoint = intersect(
                                zoneExtremes.minXMinY, zoneExtremes.maxXMinY, startX, startY, startX, nextY
                            )
                            lineWithIntersection(intersectPoint, movedByYPoint)
                            addLabel(startPoint, col, row, LABEL_TYPE_X_VALUE)
                        }
                        lastCol -> {
                            // Create correct intersection with east map
                            val intersectPoint = intersect(
                                zoneExtremes.minXMaxY, zoneExtremes.maxXMaxY, startX, startY, startX, nextY
                            )
                            lineWithIntersection(intersectPoint, startPoint)
                        }
                        else -> createLine(startPoint, movedByYPoint) // Main x lines (x lines in center of square)
                    }
                }
                if (col != firstCol) {
                    // Create y line
                    val movedByXPoint = getWGSPositionFromXY(nextX, startY)
                    when (row) {
                        firstRow -> {
                            // Create correct intersection with south map
                            val intersectPoint = intersect(
                                zoneExtremes.minXMinY, zoneExtremes.minXMaxY, startX, startY, nextX, startY
                            )
                            lineWithIntersection(intersectPoint, movedByXPoint)
                            addLabel(startPoint, col, row, LABEL_TYPE_Y_VALUE)
                        }
                        lastRow -> {
                            // Create correct intersection with north map
                            val intersectPoint = intersect(
                                zoneExtremes.maxXMinY, zoneExtremes.maxXMaxY, startX, startY, nextX, startY
                            )
                            lineWithIntersection(intersectPoint, startPoint)
                        }
                        else -> createLine(startPoint, movedByXPoint) // Main y lines (lines in center of square)
                    }
                }
            }
        }
    }

    private fun createYLineThatIntersectCorners(firstCol: Int, firstRow: Int, lastRow: Int, lastCol: Int) {
        val firstY = firstCol * scale
        if (zoneExtremes.minXMinY.y < zoneExtremes.maxXMinY.y && zoneExtremes.minXMinY.y <= firstY) {
            // Create metric graticule y line that intersects west and south boundary of the map sheet
            val startX = firstRow * scale
            val lastX = lastRow * scale
            val intersectWest = intersect(zoneExtremes.minXMinY, zoneExtremes.maxXMinY, startX, firstY, lastX, firstY)
            val intersectSouth = intersect(zoneExtremes.minXMinY, zoneExtremes.minXMaxY, startX, firstY, lastX, firstY)
            createLine(intersectSouth, intersectWest)
        } else if (zoneExtremes.maxXMinY.y < zoneExtremes.minXMinY.y && zoneExtremes.maxXMinY.y <= firstY) {
            // Create metric graticule y line that intersects west and north boundary of the map sheet
            val startX = firstRow * scale
            val lastX = lastRow * scale
            val intersectWest = intersect(zoneExtremes.minXMinY, zoneExtremes.maxXMinY, startX, firstY, lastX, firstY)
            val intersectNorth = intersect( zoneExtremes.maxXMinY, zoneExtremes.maxXMaxY, startX, firstY, lastX, firstY)
            createLine(intersectWest, intersectNorth)
        }
        val lastY = lastCol * scale + scale
        if (zoneExtremes.maxXMaxY.y < zoneExtremes.minXMaxY.y && zoneExtremes.minXMaxY.y >= lastY) {
            // Create metric graticule y line that intersects east and south boundary of the map sheet
            val startX = firstRow * scale
            val lastX = lastRow * scale
            val intersectEast = intersect(zoneExtremes.minXMaxY, zoneExtremes.maxXMaxY, startX, lastY, lastX, lastY)
            val intersectSouth = intersect(zoneExtremes.minXMinY, zoneExtremes.minXMaxY, startX, lastY, lastX, lastY)
            createLine(intersectSouth,intersectEast)
        } else if (zoneExtremes.minXMaxY.y < zoneExtremes.maxXMaxY.y && zoneExtremes.maxXMaxY.y >= lastY) {
            // Create metric graticule y line that intersects east and north boundary of the map sheet
            val startX = firstRow * scale
            val lastX = lastRow * scale
            val intersectNorth = intersect(zoneExtremes.maxXMinY, zoneExtremes.maxXMaxY, startX, lastY, lastX, lastY)
            val intersectEast = intersect(zoneExtremes.minXMaxY, zoneExtremes.maxXMaxY, startX, lastY, lastX, lastY)
            createLine(intersectNorth, intersectEast)
        }
    }

    private fun addLabel(point: Position, col: Int, row:Int, labelType: String) {
        val label = if (labelType == LABEL_TYPE_Y_VALUE) labelBy(col) else labelBy(row)
        val text = layer.createTextRenderable(point, label, scale)
        text.attributes.textOffset = Offset.center()
        text.putUserProperty(LABEL_TYPE_KEY, labelType)
        text.putUserProperty(LABEL_SCALE_TYPE, scale.toInt())
        text.putUserProperty(LABEL_X_KEY, row * scale)
        text.putUserProperty(LABEL_Y_KEY, col * scale)
        gridElements.add(GridElement(sector, text, METRIC_LABEL))
    }

    private fun createLine(firstPosition: GKCoord?, nextPosition: GKCoord?) {
        if (firstPosition != null && nextPosition != null) {
            createLine(getWGSPositionFromXY(firstPosition), getWGSPositionFromXY(nextPosition))
        }
    }
    private fun lineWithIntersection(intersectPoint: GKCoord?, anotherPoint: Position) {
        if (intersectPoint != null) createLine(anotherPoint, getWGSPositionFromXY(intersectPoint))
    }

    private fun createLine(startPoint: Position, movedByXPoint: Position) {
        val lineX = layer.createLineRenderable(mutableListOf(startPoint, movedByXPoint), PathType.LINEAR)
        gridElements.add(GridElement(sector, lineX, getTypeLine(), sector.maxLongitude))
    }


    private fun intersect(point1: GKCoord, point2: GKCoord, x3: Double, y3: Double, x4: Double, y4: Double)=
        GKLayerHelper.intersect(point1.x, point1.y, point2.x, point2.y, x3, y3, x4, y4)

    private fun labelBy(rowOrCol: Int) = ("0" + abs(rowOrCol * scale / 1000).toInt()).takeLast(2)

    private fun getTypeLine() = if (scale == SCALE_1000) GK_METRIC_GRID_1000 else GK_METRIC_GRID_2000

    private fun getWGSPositionFromXY(x: Double, y:Double): Position{
        val point = GKCoord.fromXY(x,y)
        return layer.transformToWGS(Position(point.latitude, point.longitude, 0.0))
    }

    private fun getWGSPositionFromXY(point: GKCoord) = layer.transformToWGS(Position(point.latitude, point.longitude, 0.0))

    private class ZoneExtremes(val minXMinY: GKCoord, val minXMaxY: GKCoord, val maxXMaxY:GKCoord, val maxXMinY:GKCoord)

    companion object {
        const val SCALE_1000 = 1000.0
        const val METRIC_LABEL = "metric.label"
    }
}
