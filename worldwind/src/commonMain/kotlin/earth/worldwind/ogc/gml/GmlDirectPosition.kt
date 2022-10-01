package earth.worldwind.ogc.gml

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlValue

@Serializable
data class GmlDirectPosition(
    @XmlValue(true)
    override val doubles: String,
    val srsName: String? = null,
    val srsDimension: String? = null,
    private val axisLabels: String? = null,
    private val uomLabels: String? = null
) : GmlDoubleList() {
    val axisLabelsList get() = axisLabels?.split(" ") ?: emptyList()
    val uomLabelsList get() = uomLabels?.split(" ") ?: emptyList()
}