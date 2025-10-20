package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("TileMatrixSet", WMTS10_NAMESPACE, WMTS10_PREFIX)
data class WmtsTileMatrixSet(
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
    @XmlSerialName("SupportedCRS", OWS11_NAMESPACE, OWS11_PREFIX)
    val supportedCrs: String,
    @XmlElement
    @XmlSerialName("WellKnownScaleSet", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val wellKnownScaleSet: String? = null,
    val boundingBox: OwsBoundingBox? = null,
    val tileMatrices: List<WmtsTileMatrix> = emptyList()
): OwsDescription()