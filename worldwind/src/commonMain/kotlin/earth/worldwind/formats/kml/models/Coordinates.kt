package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlValue

@Serializable
internal data class Coordinates(
    @XmlValue(true)
    val value: String? = null
) : AbstractKml() {
    override fun toString() = value ?: ""
}