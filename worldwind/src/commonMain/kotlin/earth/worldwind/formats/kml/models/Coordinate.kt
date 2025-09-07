package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlValue

/**
 * A single tuple consisting of floating point values for longitude, latitude, and altitude (in that order).
 * Longitude and latitude values are in degrees, where
 * - longitude ≥ −180 and <= 180
 * - latitude ≥ −90 and ≤ 90
 * - altitude values (optional) are in meters above sea level
 * Do not include spaces between the three values that describe a coordinate.
 */
@Serializable
internal data class Coordinate(@XmlValue(true) val value: String) : AbstractKml() {
    override fun toString() = value

    companion object {
        private const val DELIMITER = ","

        fun from(longitude: Double, latitude: Double, altitude: Double? = null) = Coordinate(toString(longitude, latitude, altitude))

        private fun toString(longitude: Double, latitude: Double, altitude: Double? = null) = buildString {
            append("$longitude$DELIMITER$latitude")
            if (altitude != null) append("$DELIMITER$altitude")
        }
    }
}