package earth.worldwind.ogc.wcs

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("ContentMetadata", WCS10_NAMESPACE, WCS10_PREFIX)
data class Wcs100ContentMetadata(
    val coverageOfferingBrief: List<Wcs100CoverageOfferingBrief> = emptyList()
)