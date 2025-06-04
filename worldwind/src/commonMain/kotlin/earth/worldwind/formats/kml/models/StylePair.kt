package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Pair")
internal data class StylePair(
    @XmlElement
    val key: String? = null,
    @XmlElement
    val styleUrl: String? = null,
)