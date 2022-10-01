package earth.worldwind.ogc.gml

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Point", GML32_NAMESPACE, GML32_PREFIX)
data class GmlPoint(
    override val id: String,
    override val srsName: String? = null,
    override val srsDimension: String? = null,
    override val axisLabels: String? = null,
    override val uomLabels: String? = null,
    @XmlSerialName("pos", GML32_NAMESPACE, GML32_PREFIX)
    val pos: GmlDirectPosition
) : GmlAbstractGeometricPrimitive()