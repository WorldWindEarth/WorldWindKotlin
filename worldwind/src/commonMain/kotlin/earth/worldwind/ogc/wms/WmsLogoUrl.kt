package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("LogoURL", WMS_NAMESPACE, WMS_PREFIX)
data class WmsLogoUrl(
    @XmlSerialName("Format", WMS_NAMESPACE, WMS_PREFIX)
    val formats: Set<String> = setOf(),
    val onlineResource: WmsOnlineResource,
    val width: Int? = null,
    val height: Int? = null
) {
    val url get() = onlineResource.url
}