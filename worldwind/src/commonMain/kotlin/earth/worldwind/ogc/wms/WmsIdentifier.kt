package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import nl.adaptivity.xmlutil.serialization.XmlValue

@Serializable
@XmlSerialName("Identifier", WMS_NAMESPACE, WMS_PREFIX)
data class WmsIdentifier(
    val authority: String,
    @XmlValue(true)
    val identifier: String,
)