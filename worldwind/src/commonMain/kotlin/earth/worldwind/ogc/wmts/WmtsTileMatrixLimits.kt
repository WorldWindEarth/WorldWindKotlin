package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("TileMatrixLimits", WMTS10_NAMESPACE, WMTS10_PREFIX)
data class WmtsTileMatrixLimits(
    @XmlElement(true)
    @XmlSerialName("TileMatrix", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val tileMatrixIdentifier: String,
    @XmlElement(true)
    @XmlSerialName("MinTileRow", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val minTileRow: Int,
    @XmlElement(true)
    @XmlSerialName("MaxTileRow", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val maxTileRow: Int,
    @XmlElement(true)
    @XmlSerialName("MinTileCol", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val minTileCol: Int,
    @XmlElement(true)
    @XmlSerialName("MaxTileCol", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val maxTileCol: Int
)