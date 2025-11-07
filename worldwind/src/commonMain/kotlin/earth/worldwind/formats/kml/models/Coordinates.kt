package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlValue

@Serializable
internal data class Coordinates(@XmlValue(true) val value: String) {
    override fun toString() = value

    companion object {
        fun fromCoordinates(list: List<Coordinate>) = Coordinates(value = toString(list))

        private fun toString(list: List<Coordinate>) = list.joinToString(" ") { it.toString() }
    }
}