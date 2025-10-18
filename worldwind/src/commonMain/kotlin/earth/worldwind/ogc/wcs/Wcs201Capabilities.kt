package earth.worldwind.ogc.wcs

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Capabilities", WCS20_NAMESPACE, WCS20_PREFIX)
data class Wcs201Capabilities(
    /**
     * Returns the document's version number.
     */
    val version: String = "2.0.1",
    /**
     * Returns the document's update sequence.
     */
    val updateSequence: String? = null,
    val serviceMetadata: Wcs201ServiceMetadata,
    val contents: Wcs201Contents

)