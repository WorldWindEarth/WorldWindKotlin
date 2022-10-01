package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import nl.adaptivity.xmlutil.serialization.XmlValue

@Serializable
@XmlSerialName("Dimension", WMS_NAMESPACE, WMS_PREFIX)
data class WmsDimension(
    val name: String,
    val units: String,
    val unitSymbol: String? = null,
    val default: String? = null,
    val multipleValues: Boolean? = null,
    val nearestValue: Boolean? = null,
    val current: Boolean? = null,
    @XmlValue(true)
    val value: String? = null
)