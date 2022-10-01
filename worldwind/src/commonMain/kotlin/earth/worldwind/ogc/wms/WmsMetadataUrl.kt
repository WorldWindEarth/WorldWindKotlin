package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("MetadataURL", WMS_NAMESPACE, WMS_PREFIX)
data class WmsMetadataUrl(
    val type: String,
    @XmlSerialName("Format", WMS_NAMESPACE, WMS_PREFIX)
    val formats: List<String> = emptyList(),
    val onlineResource: WmsOnlineResource
) {
    val url get() = onlineResource.url
}