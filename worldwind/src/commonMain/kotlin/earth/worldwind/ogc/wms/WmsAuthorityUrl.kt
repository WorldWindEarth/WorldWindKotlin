package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("AuthorityURL", WMS_NAMESPACE, WMS_PREFIX)
data class WmsAuthorityUrl(
    val name: String,
    val onlineResource: WmsOnlineResource
) {
    val url get() = onlineResource.url
}