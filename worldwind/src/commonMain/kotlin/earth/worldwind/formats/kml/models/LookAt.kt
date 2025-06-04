package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

@Serializable
internal data class LookAt(
    @XmlElement(true)
    var longitude: Double? = null,
    @XmlElement(true)
    var latitude: Double? = null,
    @XmlElement(true)
    var altitude: Double? = null,
    @XmlElement(true)
    var heading: Double? = null,
    @XmlElement(true)
    var tilt: Double? = null,
    @XmlElement(true)
    var range: Double? = null
): AbstractKml()