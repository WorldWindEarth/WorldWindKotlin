package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Service", WMS_NAMESPACE, WMS_PREFIX)
data class WmsService(
    @XmlElement(true)
    @XmlSerialName("Name", WMS_NAMESPACE, WMS_PREFIX)
    val name: String,
    @XmlElement(true)
    @XmlSerialName("Title", WMS_NAMESPACE, WMS_PREFIX)
    val title: String,
    @XmlElement(true)
    @XmlSerialName("Abstract", WMS_NAMESPACE, WMS_PREFIX)
    val abstract: String? = null,
    @XmlElement(true)
    @XmlSerialName("Fees", WMS_NAMESPACE, WMS_PREFIX)
    val fees: String? = null,
    @XmlElement(true)
    @XmlSerialName("AccessConstraints", WMS_NAMESPACE, WMS_PREFIX)
    val accessConstraints: String? = null,
    @XmlSerialName("KeywordList", WMS_NAMESPACE, WMS_PREFIX)
    @XmlChildrenName("Keyword", WMS_NAMESPACE, WMS_PREFIX)
    val keywordList: List<String> = listOf(),
    val onlineResource: WmsOnlineResource,
    val contactInformation: WmsContactInformation? = null,
    @XmlElement(true)
    @XmlSerialName("MaxWidth", WMS_NAMESPACE, WMS_PREFIX)
    val maxWidth: Int? = null,
    @XmlElement(true)
    @XmlSerialName("MaxHeight", WMS_NAMESPACE, WMS_PREFIX)
    val maxHeight: Int? = null,
    @XmlElement(true)
    @XmlSerialName("LayerLimit", WMS_NAMESPACE, WMS_PREFIX)
    val layerLimit: Int? = null
) {
    val url get() = onlineResource.url
}