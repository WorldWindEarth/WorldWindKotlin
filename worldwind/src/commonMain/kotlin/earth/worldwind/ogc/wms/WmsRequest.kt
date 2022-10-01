package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Request", WMS_NAMESPACE, WMS_PREFIX)
data class WmsRequest(
    @XmlSerialName("GetCapabilities", WMS_NAMESPACE, WMS_PREFIX)
    val getCapabilities: WmsRequestOperation,
    @XmlSerialName("GetMap", WMS_NAMESPACE, WMS_PREFIX)
    val getMap: WmsRequestOperation,
    @XmlSerialName("GetFeatureInfo", WMS_NAMESPACE, WMS_PREFIX)
    val getFeatureInfo: WmsRequestOperation? = null
)