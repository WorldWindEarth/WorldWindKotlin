package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlValue

@Serializable
internal data class Coordinate(
    @XmlValue(true)
    val value: String? = null
) : AbstractKml() {

    override fun toString() = value ?: ""

    companion object {
        private const val DELIMITER = ","

        fun from(
            longitude: Double = 0.0,
            latitude: Double = 0.0,
            altitude: Double? = null,
        ) = Coordinate(value = toString(longitude, latitude, altitude))

        private fun toString(
            longitude: Double = 0.0,
            latitude: Double = 0.0,
            altitude: Double? = null,
        ) = buildString {
            append("$longitude$DELIMITER$latitude")
            if (altitude != null) append("$DELIMITER$altitude")
        }
    }
}