package earth.worldwind.ogc.wcs

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
data class Wcs100LonLatEnvelope(
    val srsName: String,
    @XmlSerialName("pos", GML_NAMESPACE, GML_PREFIX)
    val pos: List<String>,
)