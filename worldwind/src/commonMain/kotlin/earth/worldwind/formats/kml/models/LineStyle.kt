package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

@Serializable
internal data class LineStyle(
    @XmlElement
    var color: String? = null,
    @XmlElement
    var width: Float? = null,
) : AbstractStyle()