package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
data class WmsInfoUrl(
    @XmlSerialName("Format", WMS_NAMESPACE, WMS_PREFIX)
    val formats: List<String> = emptyList(),
    val onlineResource: WmsOnlineResource
) {
    val url get() = onlineResource.url
}