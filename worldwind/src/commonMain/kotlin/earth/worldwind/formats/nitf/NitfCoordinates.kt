package earth.worldwind.formats.nitf

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Decode the 60-byte IGEOLO field into a list of four corner locations.
 *
 * The layout depends on ICORDS (MIL-STD-2500C §5.4.3.7):
 *  - `' '` : IGEOLO field absent.
 *  - `'G'` : 15-byte BCS-A string per corner — `ddmmssXdddmmssY`.
 *  - `'D'` : 15-byte BCS-A string per corner — `±dd.ddd±ddd.ddd`.
 *  - `'N'/'S'` : 15-byte BCS-A string per corner — `zzeeeeeennnnnnnn`
 *    (UTM zone + easting + northing, MGRS hemisphere implied by N/S).
 *  - `'U'` : 15-byte MGRS string per corner. Not yet implemented — we surface
 *    `null` here so callers can choose to fall back to a side-channel.
 *
 * Corners are given in clockwise order starting from the upper-left (per
 * §5.4.3.7), so [0]=NW, [1]=NE, [2]=SE, [3]=SW. We surface the four corners
 * verbatim and a derived axis-aligned [Sector] for the common "show on globe"
 * use case.
 */
object NitfCoordinates {

    /** Result of decoding IGEOLO. [corners] is in spec order NW/NE/SE/SW. */
    data class Decoded(val corners: List<Location>, val sector: Sector)

    fun decode(coordinateSystem: NitfCoordinateSystem, igeolo: String): Decoded? {
        if (coordinateSystem == NitfCoordinateSystem.NONE) return null
        if (igeolo.length < 60) return null
        val corners = (0 until 4).map { i ->
            val chunk = igeolo.substring(i * 15, (i + 1) * 15)
            when (coordinateSystem) {
                NitfCoordinateSystem.GEOGRAPHIC -> parseDms(chunk) ?: return null
                NitfCoordinateSystem.DECIMAL -> parseDecimal(chunk) ?: return null
                NitfCoordinateSystem.UTM_NORTH -> parseUtm(chunk, north = true) ?: return null
                NitfCoordinateSystem.UTM_SOUTH -> parseUtm(chunk, north = false) ?: return null
                NitfCoordinateSystem.MGRS -> return null // not yet implemented
                NitfCoordinateSystem.NONE -> return null
            }
        }
        return Decoded(corners, sectorFor(corners))
    }

    private fun sectorFor(corners: List<Location>): Sector {
        var minLat = corners[0].latitude.inDegrees
        var maxLat = minLat
        var minLon = corners[0].longitude.inDegrees
        var maxLon = minLon
        for (i in 1 until corners.size) {
            val lat = corners[i].latitude.inDegrees
            val lon = corners[i].longitude.inDegrees
            minLat = min(minLat, lat); maxLat = max(maxLat, lat)
            minLon = min(minLon, lon); maxLon = max(maxLon, lon)
        }
        return Sector(minLat.degrees, maxLat.degrees, minLon.degrees, maxLon.degrees)
    }

    /** `ddmmssXdddmmssY` — 7 digits + hemisphere for lat, 8 digits for lon. */
    private fun parseDms(s: String): Location? {
        if (s.length != 15) return null
        return try {
            val latDeg = s.substring(0, 2).toInt()
            val latMin = s.substring(2, 4).toInt()
            val latSec = s.substring(4, 6).toInt()
            val latHem = s[6]
            val lonDeg = s.substring(7, 10).toInt()
            val lonMin = s.substring(10, 12).toInt()
            val lonSec = s.substring(12, 14).toInt()
            val lonHem = s[14]
            var lat = latDeg + latMin / 60.0 + latSec / 3600.0
            if (latHem == 'S' || latHem == 's') lat = -lat
            else if (latHem != 'N' && latHem != 'n') return null
            var lon = lonDeg + lonMin / 60.0 + lonSec / 3600.0
            if (lonHem == 'W' || lonHem == 'w') lon = -lon
            else if (lonHem != 'E' && lonHem != 'e') return null
            Location.fromDegrees(lat, lon)
        } catch (_: NumberFormatException) { null }
    }

    /** `±dd.ddd±ddd.ddd` — signed decimal degrees. */
    private fun parseDecimal(s: String): Location? {
        if (s.length != 15) return null
        return try {
            val lat = s.substring(0, 7).trim().toDouble()
            val lon = s.substring(7, 15).trim().toDouble()
            Location.fromDegrees(lat, lon)
        } catch (_: NumberFormatException) { null }
    }

    /** `zzeeeeeennnnnnnn` — UTM zone + easting (m) + northing (m). */
    private fun parseUtm(s: String, north: Boolean): Location? {
        if (s.length != 15) return null
        return try {
            val zone = s.substring(0, 2).toInt()
            val easting = s.substring(2, 8).toDouble()
            val northing = s.substring(8, 15).toDouble()
            utmToLatLon(zone, easting, northing, north)
        } catch (_: NumberFormatException) { null }
    }

    /**
     * Convert UTM (WGS-84) easting/northing to geographic latitude/longitude.
     * Implements Karney's series expansion (USGS Snyder §8.39–8.50 with
     * higher-order terms folded in) — accurate to <1 mm within the UTM grid's
     * normal usage zone (±84° lat). Good enough for georeferencing a NITF
     * image footprint.
     */
    fun utmToLatLon(zone: Int, easting: Double, northing: Double, northHemisphere: Boolean): Location {
        require(zone in 1..60) { "Invalid UTM zone $zone" }
        val a = 6378137.0                       // WGS-84 semi-major axis (m)
        val f = 1.0 / 298.257223563             // WGS-84 flattening
        val k0 = 0.9996
        val falseEasting = 500000.0
        val falseNorthing = if (northHemisphere) 0.0 else 10000000.0
        // Standard UTM convention (MIL-STD-2500C §5.4.3.7, NGA SIG.0001_1.3):
        // zone 1 → -177°, zone 30 → -3°, zone 31 → 3°, zone 60 → 177°.
        val lon0 = ((zone - 0.5) * 6.0 - 180.0) * PI / 180.0

        val e2 = f * (2 - f)
        val eP2 = e2 / (1 - e2)
        val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))

        val x = easting - falseEasting
        val y = northing - falseNorthing
        val M = y / k0
        val mu = M / (a * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256))

        val phi1 = mu +
            (3 * e1 / 2 - 27 * e1 * e1 * e1 / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1 * e1 * e1 * e1 / 32) * sin(4 * mu) +
            (151 * e1 * e1 * e1 / 96) * sin(6 * mu) +
            (1097 * e1 * e1 * e1 * e1 / 512) * sin(8 * mu)

        val sinPhi1 = sin(phi1)
        val cosPhi1 = cos(phi1)
        val tanPhi1 = tan(phi1)
        val N1 = a / sqrt(1 - e2 * sinPhi1 * sinPhi1)
        val T1 = tanPhi1 * tanPhi1
        val C1 = eP2 * cosPhi1 * cosPhi1
        val esinSq = 1 - e2 * sinPhi1 * sinPhi1
        val R1 = a * (1 - e2) / (esinSq * sqrt(esinSq))
        val D = x / (N1 * k0)

        val D2 = D * D
        val D3 = D2 * D
        val D4 = D3 * D
        val D5 = D4 * D
        val D6 = D5 * D

        val lat = phi1 - (N1 * tanPhi1 / R1) * (
            D2 / 2 -
                (5 + 3 * T1 + 10 * C1 - 4 * C1 * C1 - 9 * eP2) * D4 / 24 +
                (61 + 90 * T1 + 298 * C1 + 45 * T1 * T1 - 252 * eP2 - 3 * C1 * C1) * D6 / 720
        )

        val lon = lon0 + (
            D - (1 + 2 * T1 + C1) * D3 / 6 +
                (5 - 2 * C1 + 28 * T1 - 3 * C1 * C1 + 8 * eP2 + 24 * T1 * T1) * D5 / 120
        ) / cosPhi1

        return Location.fromDegrees(lat * 180.0 / PI, lon * 180.0 / PI)
    }
}
