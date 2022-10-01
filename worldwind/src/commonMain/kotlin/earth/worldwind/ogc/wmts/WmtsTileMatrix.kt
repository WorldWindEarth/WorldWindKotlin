package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("TileMatrix", WMTS10_NAMESPACE, WMTS10_PREFIX)
data class WmtsTileMatrix(
    @XmlElement(true)
    @XmlSerialName("Title", OWS11_NAMESPACE, OWS11_PREFIX)
    override val title: String? = null,
    @XmlElement(true)
    @XmlSerialName("Abstract", OWS11_NAMESPACE, OWS11_PREFIX)
    override val abstract: String? = null,
    @XmlSerialName("Keywords", OWS11_NAMESPACE, OWS11_PREFIX)
    @XmlChildrenName("Keyword", OWS11_NAMESPACE, OWS11_PREFIX)
    override val keywords: List<String> = emptyList(),
    @XmlElement(true)
    @XmlSerialName("Identifier", OWS11_NAMESPACE, OWS11_PREFIX)
    val identifier: String,
    @XmlElement(true)
    @XmlSerialName("ScaleDenominator", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val scaleDenominator: Double,
    @XmlElement(true)
    @XmlSerialName("TopLeftCorner", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val topLeftCorner: String,
    @XmlElement(true)
    @XmlSerialName("TileWidth", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val tileWidth: Int,
    @XmlElement(true)
    @XmlSerialName("TileHeight", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val tileHeight: Int,
    @XmlElement(true)
    @XmlSerialName("MatrixWidth", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val matrixWidth: Int,
    @XmlElement(true)
    @XmlSerialName("MatrixHeight", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val matrixHeight: Int
): OwsDescription()