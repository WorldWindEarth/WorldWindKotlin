package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("BoundingBox", WMS_NAMESPACE, WMS_PREFIX)
data class WmsBoundingBox(
    val CRS: String,
    val minx: Double,
    val maxx: Double,
    val miny: Double,
    val maxy: Double,
    val resx: Double? = null,
    val resy: Double? = null,
)