package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("OnlineResource", WMS_NAMESPACE, WMS_PREFIX)
data class WmsOnlineResource(
    @XmlSerialName("type", XLINK_NAMESPACE, XLINK_PREFIX)
    val type: String = "simple",
    @XmlSerialName("href", XLINK_NAMESPACE, XLINK_PREFIX)
    val url: String
)