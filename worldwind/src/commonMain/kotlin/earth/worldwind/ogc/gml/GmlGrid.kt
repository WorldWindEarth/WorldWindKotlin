package earth.worldwind.ogc.gml

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Grid", GML32_NAMESPACE, GML32_PREFIX)
data class GmlGrid(
    override val id: String,
    override val srsName: String? = null,
    override val srsDimension: String? = null,
    @XmlElement // In GridType this property is defined as an element
    override val axisLabels: String? = null,
    override val uomLabels: String? = null,
    override val dimension: Int,
    @XmlSerialName("limits", GML32_NAMESPACE, GML32_PREFIX)
    override val limits: GmlGridLimits,
    override val axisName: List<String> = emptyList(),
) : GmlAbstractGrid()