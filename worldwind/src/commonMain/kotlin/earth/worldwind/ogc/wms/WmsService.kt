package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Service", WMS_NAMESPACE, WMS_PREFIX)
data class WmsService(
    @XmlElement
    @XmlSerialName("Name", WMS_NAMESPACE, WMS_PREFIX)
    val name: String,
    @XmlElement
    @XmlSerialName("Title", WMS_NAMESPACE, WMS_PREFIX)
    val title: String,
    @XmlElement
    @XmlSerialName("Abstract", WMS_NAMESPACE, WMS_PREFIX)
    val abstract: String? = null,
    @XmlElement
    @XmlSerialName("Fees", WMS_NAMESPACE, WMS_PREFIX)
    val fees: String? = null,
    @XmlElement
    @XmlSerialName("AccessConstraints", WMS_NAMESPACE, WMS_PREFIX)
    val accessConstraints: String? = null,
    @XmlSerialName("KeywordList", WMS_NAMESPACE, WMS_PREFIX)
    @XmlChildrenName("Keyword", WMS_NAMESPACE, WMS_PREFIX)
    val keywordList: List<String> = listOf(),
    val onlineResource: WmsOnlineResource,
    val contactInformation: WmsContactInformation? = null,
    @XmlElement
    @XmlSerialName("MaxWidth", WMS_NAMESPACE, WMS_PREFIX)
    val maxWidth: Int? = null,
    @XmlElement
    @XmlSerialName("MaxHeight", WMS_NAMESPACE, WMS_PREFIX)
    val maxHeight: Int? = null,
    @XmlElement
    @XmlSerialName("LayerLimit", WMS_NAMESPACE, WMS_PREFIX)
    val layerLimit: Int? = null
) {
    val url get() = onlineResource.url
}