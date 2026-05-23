package earth.worldwind.formats.shapefile

import earth.worldwind.geom.coords.Hemisphere
import earth.worldwind.geom.coords.UTMCoord

/**
 * Projection (`.prj`) parser. Distinguishes geographic and projected coordinate systems
 * via OGC well-known text. For projected systems we recognize the UTM family — the only
 * projection commonly distributed with raw shapefiles — and expose a [Projection] that
 * can convert easting/northing pairs into lon/lat. Other projected systems are flagged
 * as projected-but-unsupported; callers can treat coordinates as geographic at their
 * own risk.
 */
class PrjFile(val text: String) {
    val coordinateSystem: CoordinateSystem

    /**
     * Coordinate transform implied by this `.prj`. Always non-null after construction:
     * geographic systems get a [Projection.Geographic] that just normalizes lon/lat;
     * UTM systems get a [Projection.Utm]; everything else gets [Projection.Unknown]
     * that leaves coords untouched.
     */
    val projection: Projection

    /** UTM zone parsed from the WKT, or `null` if this PRJ isn't UTM. */
    val utmZone: Int?
    /** Hemisphere parsed from the WKT, or `null` if this PRJ isn't UTM. */
    val utmHemisphere: Hemisphere?

    init {
        val upper = text.trim().uppercase()
        coordinateSystem = when {
            upper.isEmpty() -> CoordinateSystem.UNKNOWN
            // PROJCS always nests GEOGCS, so check the outermost keyword first.
            PROJCS_PATTERN.containsMatchIn(upper) -> CoordinateSystem.PROJECTED
            GEOGCS_PATTERN.containsMatchIn(upper) -> CoordinateSystem.GEOGRAPHIC
            else -> CoordinateSystem.UNKNOWN
        }

        when (coordinateSystem) {
            CoordinateSystem.GEOGRAPHIC -> {
                utmZone = null
                utmHemisphere = null
                projection = Projection.Geographic
            }
            CoordinateSystem.PROJECTED -> {
                // UTM detection: most shapefiles either name the system "UTM zone NN[NS]"
                // (e.g. "WGS 84 / UTM zone 33N") or carry an EPSG AUTHORITY code in the
                // 326NN (north) / 327NN (south) blocks. Try both.
                val match = UTM_NAME_PATTERN.find(upper)
                val epsg = EPSG_AUTHORITY_PATTERN.find(upper)?.groupValues?.get(1)?.toIntOrNull()
                val parsedZone: Int?
                val parsedHemisphere: Hemisphere?
                when {
                    match != null -> {
                        parsedZone = match.groupValues[1].toIntOrNull()
                        parsedHemisphere = if (match.groupValues[2] == "S") Hemisphere.S else Hemisphere.N
                    }
                    epsg != null && epsg in 32601..32660 -> {
                        parsedZone = epsg - 32600
                        parsedHemisphere = Hemisphere.N
                    }
                    epsg != null && epsg in 32701..32760 -> {
                        parsedZone = epsg - 32700
                        parsedHemisphere = Hemisphere.S
                    }
                    else -> {
                        parsedZone = null
                        parsedHemisphere = null
                    }
                }
                utmZone = parsedZone
                utmHemisphere = parsedHemisphere
                projection = if (parsedZone != null && parsedHemisphere != null) {
                    Projection.Utm(parsedZone, parsedHemisphere)
                } else {
                    Projection.Unknown
                }
            }
            CoordinateSystem.UNKNOWN -> {
                utmZone = null
                utmHemisphere = null
                projection = Projection.Unknown
            }
        }
    }

    val isGeographicCoordinateSystem: Boolean get() = coordinateSystem == CoordinateSystem.GEOGRAPHIC
    val isProjectedCoordinateSystem: Boolean get() = coordinateSystem == CoordinateSystem.PROJECTED
    val isKnownCoordinateSystem: Boolean get() = coordinateSystem != CoordinateSystem.UNKNOWN
    val isUnknownCoordinateSystem: Boolean get() = coordinateSystem == CoordinateSystem.UNKNOWN

    enum class CoordinateSystem { GEOGRAPHIC, PROJECTED, UNKNOWN }

    /**
     * Transform a shapefile X/Y pair into geographic longitude/latitude in degrees. The
     * input pair is `(easting, northing)` for projected files and `(longitude, latitude)`
     * for geographic files.
     */
    sealed class Projection {
        /** Returns `[longitude, latitude]` in degrees. */
        abstract fun toGeographic(x: Double, y: Double): DoubleArray

        object Geographic : Projection() {
            override fun toGeographic(x: Double, y: Double): DoubleArray = doubleArrayOf(x, y)
        }

        /** UTM → lat/lon via [UTMCoord]. */
        class Utm(val zone: Int, val hemisphere: Hemisphere) : Projection() {
            override fun toGeographic(x: Double, y: Double): DoubleArray {
                val coord = UTMCoord.fromUTM(zone, hemisphere, x, y)
                return doubleArrayOf(coord.longitude.inDegrees, coord.latitude.inDegrees)
            }
        }

        /** Unsupported projection. Returns the input unchanged so callers can still see *some* output. */
        object Unknown : Projection() {
            override fun toGeographic(x: Double, y: Double): DoubleArray = doubleArrayOf(x, y)
        }
    }

    companion object {
        private val GEOGCS_PATTERN = Regex("""GEOGCS\s*[\[(]""")
        private val PROJCS_PATTERN = Regex("""PROJCS\s*[\[(]""")
        // Matches "UTM ZONE 33N" / "UTM_ZONE_33N" / "UTM-ZONE-33-N" etc. against the
        // uppercased WKT. Tolerates the optional space between digits and N/S.
        private val UTM_NAME_PATTERN = Regex("""UTM[\s_-]*ZONE[\s_-]*(\d{1,2})[\s_-]*([NS])""")
        // PROJCS uppercased: AUTHORITY["EPSG","32633"]
        private val EPSG_AUTHORITY_PATTERN = Regex("""AUTHORITY\s*[\[(]\s*"EPSG"\s*,\s*"(\d+)"""")
    }
}
