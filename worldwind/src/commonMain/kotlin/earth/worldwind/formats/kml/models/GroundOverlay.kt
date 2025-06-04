package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

@Serializable
internal data class GroundOverlay(
    @XmlElement(true)
    var name: String? = null,
    @XmlElement(true)
    var visibility: Int? = null,
    @XmlElement(true)
    var description: String? = null,
    @XmlElement(true)
    var lookAt: LookAt? = null,
): AbstractKml()