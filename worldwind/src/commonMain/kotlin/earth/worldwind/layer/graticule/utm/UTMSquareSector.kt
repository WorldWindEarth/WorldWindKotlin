package earth.worldwind.layer.graticule.utm

import earth.worldwind.geom.Angle.Companion.NEG180
import earth.worldwind.geom.Angle.Companion.NEG90
import earth.worldwind.geom.Angle.Companion.POS180
import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.Location
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.coords.Hemisphere
import earth.worldwind.layer.graticule.AbstractGraticuleTile
import earth.worldwind.render.RenderContext
import kotlin.math.abs
import kotlin.math.sign

/**
 * Represent a generic UTM/UPS square area
 */
abstract class UTMSquareSector(
    layer: AbstractUTMGraticuleLayer, val UTMZone: Int, val hemisphere: Hemisphere,
    val UTMZoneSector: Sector, val SWEasting: Double, val SWNorthing: Double, val size: Double
): AbstractGraticuleTile(layer, Sector()) {
    val squareCenter = layer.computePosition(UTMZone, hemisphere, SWEasting + size / 2, SWNorthing + size / 2)
    // Four corners position
    var sw = layer.computePosition(UTMZone, hemisphere, SWEasting, SWNorthing)
    var se = layer.computePosition(UTMZone, hemisphere, SWEasting + size, SWNorthing)
    var nw = layer.computePosition(UTMZone, hemisphere, SWEasting, SWNorthing + size)
    var ne = layer.computePosition(UTMZone, hemisphere, SWEasting + size, SWNorthing + size)
    var boundingSector = boundingSector(adjustDateLineCrossingPoints()).apply { if (!isInsideGridZone) intersect(UTMZoneSector) }.also { sector.copy(it) }
    var centroid = boundingSector.centroid(Location())
    val isTruncated = !isInsideGridZone
    override val layer get() = super.layer as AbstractUTMGraticuleLayer
    /**
     * Determines whether this square is fully inside its parent grid zone.
     */
    private val isInsideGridZone get() = isPositionInside(nw) && isPositionInside(ne) && isPositionInside(sw) && isPositionInside(se)
    /**
     * Determines whether this square is fully outside its parent grid zone.
     */
    val isOutsideGridZone = !isPositionInside(nw) && !isPositionInside(ne) && !isPositionInside(sw) && !isPositionInside(se)

    private fun adjustDateLineCrossingPoints(): Iterable<Location> {
        val corners = listOf(sw, se, nw, ne)
        if (!locationsCrossDateLine(corners)) return corners

        var lonSign = 0.0
        for (corner in corners) if (abs(corner.longitude.inDegrees) != 180.0) lonSign = sign(corner.longitude.inDegrees)
        if (lonSign == 0.0) return corners

        if (abs(sw.longitude.inDegrees) == 180.0 && sign(sw.longitude.inDegrees) != lonSign)
            sw = Position(sw.latitude, -sw.longitude, sw.altitude)
        if (abs(se.longitude.inDegrees) == 180.0 && sign(se.longitude.inDegrees) != lonSign)
            se = Position(se.latitude, -se.longitude, se.altitude)
        if (abs(nw.longitude.inDegrees) == 180.0 && sign(nw.longitude.inDegrees) != lonSign)
            nw = Position(nw.latitude, -nw.longitude, nw.altitude)
        if (abs(ne.longitude.inDegrees) == 180.0 && sign(ne.longitude.inDegrees) != lonSign)
            ne = Position(ne.latitude, -ne.longitude, ne.altitude)

        return listOf(sw, se, nw, ne)
    }

    private fun locationsCrossDateLine(locations: Iterable<Location>): Boolean {
        var pos: Location? = null
        for (posNext in locations) {
            if (pos != null) {
                // A segment cross the line if end pos have different longitude signs
                // and are more than 180 degrees longitude apart
                if (sign(pos.longitude.inDegrees) != sign(posNext.longitude.inDegrees)) {
                    val delta = abs(pos.longitude.inDegrees - posNext.longitude.inDegrees)
                    if (delta > 180 && delta < 360) return true
                }
            }
            pos = posNext
        }
        return false
    }

    private fun boundingSector(locations: Iterable<Location>): Sector {
        var minLat = POS90
        var minLon = POS180
        var maxLat = NEG90
        var maxLon = NEG180
        for (p in locations) {
            val lat = p.latitude
            if (lat.inDegrees < minLat.inDegrees) minLat = lat
            if (lat.inDegrees > maxLat.inDegrees) maxLat = lat
            val lon = p.longitude
            if (lon.inDegrees < minLon.inDegrees) minLon = lon
            if (lon.inDegrees > maxLon.inDegrees) maxLon = lon
        }
        return Sector(minLat, maxLat, minLon, maxLon)
    }

    fun boundingSector(pA: Location, pB: Location): Sector {
        var minLat = pA.latitude
        var minLon = pA.longitude
        var maxLat = pA.latitude
        var maxLon = pA.longitude
        if (pB.latitude.inDegrees < minLat.inDegrees) minLat = pB.latitude
        else if (pB.latitude.inDegrees > maxLat.inDegrees) maxLat = pB.latitude
        if (pB.longitude.inDegrees < minLon.inDegrees) minLon = pB.longitude
        else if (pB.longitude.inDegrees > maxLon.inDegrees) maxLon = pB.longitude
        return Sector(minLat, maxLat, minLon, maxLon)
    }

    fun isPositionInside(position: Location) = UTMZoneSector.contains(position)

    override fun getSizeInPixels(rc: RenderContext): Double {
        val centerPoint = layer.getSurfacePoint(rc, centroid.latitude, centroid.longitude)
        val distance = rc.cameraPoint.distanceTo(centerPoint)
        return size / rc.pixelSizeAtDistance(distance) / rc.densityFactor
    }

    companion object {
        const val MIN_CELL_SIZE_PIXELS = 50
    }
}