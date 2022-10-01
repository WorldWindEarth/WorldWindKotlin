package earth.worldwind.ogc.wcs

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("CoverageDescriptions", WCS20_NAMESPACE, WCS20_PREFIX)
data class Wcs201CoverageDescriptions(
    val coverageDescriptions: List<Wcs201CoverageDescription> = emptyList()
) {
    fun getCoverageDescription(identifier: String) = coverageDescriptions.firstOrNull {
        coverageDescription -> coverageDescription.id == identifier
    }
}