package earth.worldwind.ogc.wcs

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("CoverageSummary", WCS20_NAMESPACE, WCS20_PREFIX)
data class Wcs201CoverageSummary(
    @XmlElement
    @XmlSerialName("CoverageId", WCS20_NAMESPACE, WCS20_PREFIX)
    val coverageId: String,
    @XmlElement
    @XmlSerialName("CoverageSubtype", WCS20_NAMESPACE, WCS20_PREFIX)
    val coverageSubtype: String,
)