package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Layer", WMTS10_NAMESPACE, WMTS10_PREFIX)
data class WmtsLayer(
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
    val boundingBoxes: List<OwsBoundingBox> = emptyList(),
    @XmlSerialName("WGS84BoundingBox", OWS11_NAMESPACE, OWS11_PREFIX)
    val wgs84BoundingBox: OwsBoundingBox? = null,
    @XmlSerialName("Metadata", OWS11_NAMESPACE, OWS11_PREFIX)
    val metadata: List<OwsOnlineResource> = emptyList(),
    val styles: List<WmtsStyle> = emptyList(),
    @XmlSerialName("Format", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val formats: List<String> = emptyList(),
    @XmlSerialName("InfoFormat", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val infoFormats: List<String> = emptyList(),
    val tileMatrixSetLinks: List<WmtsTileMatrixSetLink> = emptyList(),
    val resourceUrls: List<WmtsResourceUrl> = emptyList(),
    val dimensions: List<WmtsDimension> = emptyList(),
): OwsDescription() {
    val layerSupportedTileMatrixSets get() = tileMatrixSetLinks.flatMap { link ->
        capabilities.tileMatrixSets.filter { set -> set.identifier == link.identifier }
    }
    @Transient
    lateinit var capabilities: WmtsCapabilities
}