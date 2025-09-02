package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

@Serializable
internal data class LatLonBox(
    @XmlElement
    var north: Double? = null,
    @XmlElement
    var south: Double? = null,
    @XmlElement
    var east: Double? = null,
    @XmlElement
    var west: Double? = null,
    @XmlElement
    var rotation: Double? = null,
): AbstractStyle()