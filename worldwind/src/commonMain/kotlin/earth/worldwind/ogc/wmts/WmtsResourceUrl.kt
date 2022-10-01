package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("ResourceURL", WMTS10_NAMESPACE, WMTS10_PREFIX)
data class WmtsResourceUrl(
    val format: String,
    val resourceType: String,
    val template: String
)