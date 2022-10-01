package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("TileMatrixSetLink", WMTS10_NAMESPACE, WMTS10_PREFIX)
data class WmtsTileMatrixSetLink(
    @XmlElement(true)
    @XmlSerialName("TileMatrixSet", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val identifier: String,
    @XmlSerialName("TileMatrixSetLimits", WMTS10_NAMESPACE, WMTS10_PREFIX)
    @XmlChildrenName("TileMatrixLimits", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val tileMatrixSetLimits: List<WmtsTileMatrixLimits> = emptyList()
)