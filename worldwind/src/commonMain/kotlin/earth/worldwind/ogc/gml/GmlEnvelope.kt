package earth.worldwind.ogc.gml

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Envelope", GML32_NAMESPACE, GML32_PREFIX)
data class GmlEnvelope(
    @XmlSerialName("lowerCorner", GML32_NAMESPACE, GML32_PREFIX)
    val lowerCorner: GmlDirectPosition,
    @XmlSerialName("upperCorner", GML32_NAMESPACE, GML32_PREFIX)
    val upperCorner: GmlDirectPosition,
    val srsName: String? = null,
    val srsDimension: String? = null,
    private val axisLabels: String? = null,
    private val uomLabels: String? = null
) {
    val axisLabelsList get() = axisLabels?.split(" ") ?: emptyList()
    val uomLabelsList get() = uomLabels?.split(" ") ?: emptyList()
}