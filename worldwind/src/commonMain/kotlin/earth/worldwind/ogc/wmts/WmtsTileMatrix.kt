package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("TileMatrix", WMTS10_NAMESPACE, WMTS10_PREFIX)
data class WmtsTileMatrix(
    @XmlElement
    @XmlSerialName("Title", OWS11_NAMESPACE, OWS11_PREFIX)
    override val title: String? = null,
    @XmlElement
    @XmlSerialName("Abstract", OWS11_NAMESPACE, OWS11_PREFIX)
    override val abstract: String? = null,
    @XmlSerialName("Keywords", OWS11_NAMESPACE, OWS11_PREFIX)
    @XmlChildrenName("Keyword", OWS11_NAMESPACE, OWS11_PREFIX)
    override val keywords: List<String> = emptyList(),
    @XmlElement
    @XmlSerialName("Identifier", OWS11_NAMESPACE, OWS11_PREFIX)
    val identifier: String,
    @XmlElement
    @XmlSerialName("ScaleDenominator", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val scaleDenominator: Double,
    @XmlElement
    @XmlSerialName("TopLeftCorner", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val topLeftCorner: String,
    @XmlElement
    @XmlSerialName("TileWidth", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val tileWidth: Int,
    @XmlElement
    @XmlSerialName("TileHeight", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val tileHeight: Int,
    @XmlElement
    @XmlSerialName("MatrixWidth", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val matrixWidth: Int,
    @XmlElement
    @XmlSerialName("MatrixHeight", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val matrixHeight: Int
): OwsDescription()