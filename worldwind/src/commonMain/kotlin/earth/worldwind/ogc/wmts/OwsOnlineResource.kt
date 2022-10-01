package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("OnlineResource", OWS11_NAMESPACE, OWS11_PREFIX)
data class OwsOnlineResource(
    @XmlSerialName("href", XLINK_NAMESPACE, XLINK_PREFIX)
    val url: String
)