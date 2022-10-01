package earth.worldwind.ogc.wcs

import earth.worldwind.ogc.gml.*
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("CoverageDescription", WCS20_NAMESPACE, WCS20_PREFIX)
data class Wcs201CoverageDescription(
    @XmlElement(true)
    @XmlSerialName("CoverageId", WCS20_NAMESPACE, WCS20_PREFIX)
    override val id: String,
    @XmlSerialName("boundedBy", GML32_NAMESPACE, GML32_PREFIX)
    override val boundedBy: GmlBoundingShape? = null,
    @XmlSerialName("domainSet", GML32_NAMESPACE, GML32_PREFIX)
    val domainSet: GmlDomainSet,
) : GmlAbstractFeature()