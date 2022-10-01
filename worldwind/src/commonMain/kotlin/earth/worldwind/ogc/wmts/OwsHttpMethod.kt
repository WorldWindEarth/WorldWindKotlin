package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
data class OwsHttpMethod(
    @XmlSerialName("href", XLINK_NAMESPACE, XLINK_PREFIX)
    val url: String,
    val constraints: List<OwsConstraint> = emptyList()
)