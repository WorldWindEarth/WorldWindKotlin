package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

@Serializable
internal data class StyleMap(
    var id: String? = null,
    @XmlElement
    val pairs: List<StylePair>? = null,
)