package earth.worldwind.ogc.gml

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("GridEnvelope", GML32_NAMESPACE, GML32_PREFIX)
data class GmlGridEnvelope(
    @XmlSerialName("low", GML32_NAMESPACE, GML32_PREFIX)
    val low: GmlIntegerList,
    @XmlSerialName("high", GML32_NAMESPACE, GML32_PREFIX)
    val high: GmlIntegerList
)