package earth.worldwind.geom.coords

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location
import earth.worldwind.util.format.format
import kotlin.jvm.JvmStatic
import kotlin.math.abs

/**
 * Geographic latitude/longitude in WGS-84, with strict text parsing and the standard set of
 * decimal-degrees / degrees-minutes / degrees-minutes-seconds formatters.
 */
class WGSCoord private constructor(val latitude: Angle, val longitude: Angle) {
    companion object {
        // A signed decimal token. An embedded hyphen (e.g. "36-30-25") yields a token that does
        // not match this pattern and causes the parser to reject the input.
        private val NUMBER_REGEX = Regex("""[+-]?\d+(?:\.\d+)?""")
        // Zero-width match at every boundary between a digit and an N/S/E/W letter, so the
        // single replace below splits "36N", "N36", "5N5", etc. apart.
        private val NSEW_DIGIT_BOUNDARY = Regex("""(?<=[0-9])(?=[NSEWnsew])|(?<=[NSEWnsew])(?=[0-9])""")
        // Decoration characters treated as separators.
        private val DECORATION_REGEX = Regex("""[°′″'"*,;]""")

        @JvmStatic
        fun fromLatLon(latitude: Angle, longitude: Angle) = WGSCoord(latitude, longitude)

        /**
         * Parses a WGS-84 latitude/longitude pair from a textual representation. Supports
         * decimal degrees, degrees-minutes (D M) and degrees-minutes-seconds (D M S) on each
         * axis, with optional N/S and E/W hemisphere markers. The decoration characters
         * `°` `′` `″` `'` `"` `*` `,` `;` and whitespace act as separators.
         *
         * The parser rejects every input it cannot interpret as a valid coordinate pair —
         * blank input, unrecognized tokens, hemisphere markers without a number, repeated
         * hemisphere markers, an odd number of unmarked tokens, more than three D/M/S parts,
         * negative minutes or seconds, and angles outside `[-90, 90]` / `[-180, 180]`.
         *
         * @throws IllegalArgumentException when the string is not a well-formed coordinate pair.
         */
        @JvmStatic
        fun fromString(coordinates: String): WGSCoord {
            require(coordinates.isNotBlank()) { "Empty coordinates string" }
            val prefix = "Coordinates string '$coordinates'"

            val tokens = coordinates
                .replace(NSEW_DIGIT_BOUNDARY, " ")
                .replace(DECORATION_REGEX, " ")
                .split(' ', '\t', '\n', '\r')
                .filter { it.isNotEmpty() }

            var latParts: List<Double>? = null
            var latSign = 1
            var lonParts: List<Double>? = null
            var lonSign = 1
            val pending = mutableListOf<Double>()

            fun assignFromMarker(marker: Char) {
                require(pending.isNotEmpty()) {
                    "$prefix has hemisphere marker '$marker' without a numeric value"
                }
                when (marker.uppercaseChar()) {
                    'N', 'S' -> {
                        require(latParts == null) { "$prefix contains multiple latitude (N/S) hemispheres" }
                        latParts = pending.toList()
                        latSign = if (marker.uppercaseChar() == 'S') -1 else 1
                    }
                    'E', 'W' -> {
                        require(lonParts == null) { "$prefix contains multiple longitude (E/W) hemispheres" }
                        lonParts = pending.toList()
                        lonSign = if (marker.uppercaseChar() == 'W') -1 else 1
                    }
                }
                pending.clear()
            }

            for (token in tokens) when {
                NUMBER_REGEX.matches(token) -> pending.add(token.toDouble())
                token.length == 1 && token[0].uppercaseChar() in "NSEW" -> assignFromMarker(token[0])
                else -> throw IllegalArgumentException("$prefix contains an unrecognized token '$token'")
            }

            if (pending.isNotEmpty()) when {
                latParts == null && lonParts == null -> {
                    require(pending.size in 2..6 && pending.size % 2 == 0) {
                        "$prefix has ${pending.size} unmarked numeric tokens; cannot split into latitude and longitude"
                    }
                    val half = pending.size / 2
                    latParts = pending.subList(0, half).toList()
                    lonParts = pending.subList(half, pending.size).toList()
                }
                latParts == null -> latParts = pending.toList()
                lonParts == null -> lonParts = pending.toList()
                else -> throw IllegalArgumentException("$prefix has extra numeric tokens after hemisphere markers")
            }

            val latGroup = requireNotNull(latParts) { "$prefix is missing latitude" }
            val lonGroup = requireNotNull(lonParts) { "$prefix is missing longitude" }

            val lat = latSign * partsToDegrees(latGroup, "latitude", prefix)
            val lon = lonSign * partsToDegrees(lonGroup, "longitude", prefix)
            require(abs(lat) <= 90.0) { "$prefix produced latitude $lat° outside [-90, 90]" }
            require(abs(lon) <= 180.0) { "$prefix produced longitude $lon° outside [-180, 180]" }
            return WGSCoord(lat.degrees, lon.degrees)
        }

        private fun partsToDegrees(parts: List<Double>, axis: String, prefix: String): Double {
            require(parts.size in 1..3) {
                "$prefix has ${parts.size} numeric parts for $axis (expected 1 to 3 for D[ M[ S]])"
            }
            val deg = parts[0]
            val min = if (parts.size > 1) parts[1] else 0.0
            val sec = if (parts.size > 2) parts[2] else 0.0
            require(min >= 0.0) { "$prefix has negative minutes ($min) for $axis" }
            require(sec >= 0.0) { "$prefix has negative seconds ($sec) for $axis" }
            val sign = if (deg < 0.0) -1.0 else 1.0
            return sign * (abs(deg) + min / 60.0 + sec / 3600.0)
        }
    }

    fun toLocation() = Location(latitude, longitude)

    override fun toString() = toDDString()

    fun toDDString() = "%s%09.6f°, %s%010.6f°".format(
        latitude.latitudeLetter, abs(latitude.inDegrees),
        longitude.longitudeLetter, abs(longitude.inDegrees)
    )

    fun toDMString(): String {
        val lat = latitude.toDMS()
        val lon = longitude.toDMS()
        return "%s%02d°%06.3f′, %s%03d°%06.3f′".format(
            latitude.latitudeLetter, lat[1], lat[2] + lat[3] / 60.0,
            longitude.longitudeLetter, lon[1], lon[2] + lon[3] / 60.0
        )
    }

    fun toDMSString(): String {
        val lat = latitude.toDMS()
        val lon = longitude.toDMS()
        return "%s%02d°%02d′%04.1f″, %s%03d°%02d′%04.1f″".format(
            latitude.latitudeLetter, lat[1], lat[2], lat[3],
            longitude.longitudeLetter, lon[1], lon[2], lon[3]
        )
    }
}
