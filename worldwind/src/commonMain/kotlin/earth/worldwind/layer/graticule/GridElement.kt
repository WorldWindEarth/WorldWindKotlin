package earth.worldwind.layer.graticule

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Sector
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable

class GridElement(val sector: Sector, val renderable: Renderable, val type: String, val value: Angle) {
    constructor(sector: Sector, renderable: Renderable, type: String): this(sector, renderable, type, ZERO)

    fun isInView(rc: RenderContext) = sector.intersectsOrNextTo(rc.terrain!!.sector)

    companion object {
        const val TYPE_LINE = "GridElement_Line"
        const val TYPE_LINE_NORTH = "GridElement_LineNorth"
        const val TYPE_LINE_SOUTH = "GridElement_LineSouth"
        const val TYPE_LINE_WEST = "GridElement_LineWest"
        const val TYPE_LINE_EAST = "GridElement_LineEast"
        const val TYPE_LINE_NORTHING = "GridElement_LineNorthing"
        const val TYPE_LINE_EASTING = "GridElement_LineEasting"
        const val TYPE_GRIDZONE_LABEL = "GridElement_GridZoneLabel"
        const val TYPE_LONGITUDE_LABEL = "GridElement_LongitudeLabel"
        const val TYPE_LATITUDE_LABEL = "GridElement_LatitudeLabel"
    }
}