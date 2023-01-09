package earth.worldwind.ogc.gml

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlPolyChildren

@Serializable
data class GmlDomainSet(
    @XmlPolyChildren(["earth.worldwind.ogc.gml.GmlPoint", "earth.worldwind.ogc.gml.GmlGrid", "earth.worldwind.ogc.gml.GmlRectifiedGrid"])
    val geometry: GmlAbstractGeometry
)