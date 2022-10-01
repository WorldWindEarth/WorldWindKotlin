package earth.worldwind.ogc.gml

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlPolyChildren

@Serializable
data class GmlDomainSet(
    @XmlPolyChildren(["worldwind.ogc.gml.GmlPoint", "worldwind.ogc.gml.GmlGrid", "worldwind.ogc.gml.GmlRectifiedGrid"])
    val geometry: GmlAbstractGeometry
)