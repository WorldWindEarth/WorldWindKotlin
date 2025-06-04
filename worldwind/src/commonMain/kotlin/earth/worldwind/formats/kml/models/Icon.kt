package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

@Serializable
internal data class Icon(
    @XmlElement
    var href: String? = null
) : AbstractStyle()