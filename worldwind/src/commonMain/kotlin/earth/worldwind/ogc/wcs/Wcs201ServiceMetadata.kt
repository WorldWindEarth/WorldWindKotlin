package earth.worldwind.ogc.wcs

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("ServiceMetadata", WCS20_NAMESPACE, WCS20_PREFIX)
data class Wcs201ServiceMetadata(
    val formatSupported: List<String> = emptyList()
)