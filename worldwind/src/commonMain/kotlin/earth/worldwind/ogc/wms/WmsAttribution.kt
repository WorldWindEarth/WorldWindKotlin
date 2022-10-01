package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Attribution", WMS_NAMESPACE, WMS_PREFIX)
data class WmsAttribution(
    @XmlElement(true)
    @XmlSerialName("Title", WMS_NAMESPACE, WMS_PREFIX)
    val title: String? = null,
    val onlineResource: WmsOnlineResource? = null,
    val logoURL: WmsLogoUrl? = null
) {
    val url get() = onlineResource?.url
}