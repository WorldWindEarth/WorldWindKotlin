package earth.worldwind.ogc.wcs

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("WCS_Capabilities", WCS10_NAMESPACE, WCS10_PREFIX)
data class Wcs100Capabilities(
    /**
     * Returns the document's version number.
     */
    val version: String = "1.0.0",
    /**
     * Returns the document's update sequence.
     */
    val updateSequence: String? = null,
    val contentMetadata: Wcs100ContentMetadata,
)