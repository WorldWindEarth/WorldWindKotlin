package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
data class WmsRequestOperation(
    @XmlSerialName("Format", WMS_NAMESPACE, WMS_PREFIX)
    val formats: List<String> = emptyList(),
    val dcpType: WmsDcpType
) {
    val getUrl get() = dcpType.getHref
    val postUrl get() = dcpType.postHref
}