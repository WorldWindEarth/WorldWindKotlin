package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Style", WMS_NAMESPACE, WMS_PREFIX)
data class WmsStyle(
    @XmlElement(true)
    @XmlSerialName("Name", WMS_NAMESPACE, WMS_PREFIX)
    val name: String,
    @XmlElement(true)
    @XmlSerialName("Title", WMS_NAMESPACE, WMS_PREFIX)
    val title: String,
    @XmlElement(true)
    @XmlSerialName("Abstract", WMS_NAMESPACE, WMS_PREFIX)
    val abstract: String? = null,
    @XmlSerialName("LegendURL", WMS_NAMESPACE, WMS_PREFIX)
    val legendUrls: List<WmsLogoUrl> = emptyList(),
    @XmlSerialName("StyleSheetURL", WMS_NAMESPACE, WMS_PREFIX)
    val styleSheetUrl: WmsInfoUrl? = null,
    @XmlSerialName("StyleURL", WMS_NAMESPACE, WMS_PREFIX)
    val styleUrl: WmsInfoUrl? = null
)