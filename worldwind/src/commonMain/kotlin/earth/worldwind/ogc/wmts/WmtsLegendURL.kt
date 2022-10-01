package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("LegendURL", WMTS10_NAMESPACE, WMTS10_PREFIX)
data class WmtsLegendURL(
    @XmlSerialName("href", XLINK_NAMESPACE, XLINK_PREFIX)
    val url: String,
    val format: String
)