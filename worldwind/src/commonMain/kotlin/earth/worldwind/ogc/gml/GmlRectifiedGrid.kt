package earth.worldwind.ogc.gml

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("RectifiedGrid", GML32_NAMESPACE, GML32_PREFIX)
data class GmlRectifiedGrid(
    override val id: String,
    override val srsName: String? = null,
    override val srsDimension: String? = null,
    @XmlElement(true) // In GridType this property is defined as an element
    override val axisLabels: String? = null,
    override val uomLabels: String? = null,
    override val dimension: Int,
    @XmlSerialName("limits", GML32_NAMESPACE, GML32_PREFIX)
    override val limits: GmlGridLimits,
    override val axisName: List<String> = emptyList(),
    @XmlSerialName("origin", GML32_NAMESPACE, GML32_PREFIX)
    var origin: GmlPointProperty,
    @XmlSerialName("offsetVector", GML32_NAMESPACE, GML32_PREFIX)
    val offsetVector: List<GmlVector> = emptyList(),
): GmlAbstractGrid()