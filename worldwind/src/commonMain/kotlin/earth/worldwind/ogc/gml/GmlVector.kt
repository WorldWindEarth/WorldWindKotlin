package earth.worldwind.ogc.gml

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlValue

@Serializable
data class GmlVector(
    @XmlValue(true)
    override val doubles: String,
    val srsName: String? = null,
    val srsDimension: String? = null,
    val axisLabels: String? = null,
    val uomLabels: String? = null
): GmlDoubleList() {
    val axisLabelsList get() = axisLabels?.split(" ") ?: emptyList()
    val uomLabelsList get() = uomLabels?.split(" ") ?: emptyList()
}