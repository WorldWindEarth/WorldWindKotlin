package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

@Serializable
internal data class IconStyle(
    @XmlElement
    var scale: Double? = null,
    @XmlElement
    var icon: Icon? = null,
    @XmlElement
    var color: String? = null,
) : AbstractStyle()