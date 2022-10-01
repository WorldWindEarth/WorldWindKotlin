package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Exception", WMS_NAMESPACE, WMS_PREFIX)
data class WmsException(
    @XmlSerialName("Format", WMS_NAMESPACE, WMS_PREFIX)
    val formats: List<String> = emptyList()
)