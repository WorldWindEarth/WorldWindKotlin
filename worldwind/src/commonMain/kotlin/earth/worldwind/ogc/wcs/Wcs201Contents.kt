package earth.worldwind.ogc.wcs

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Contents", WCS20_NAMESPACE, WCS20_PREFIX)
data class Wcs201Contents(
    val coverageSummary: List<Wcs201CoverageSummary> = emptyList()
)