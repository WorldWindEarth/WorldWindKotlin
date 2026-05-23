package earth.worldwind.ogc.wfs

import earth.worldwind.ogc.wmts.OWS11_NAMESPACE
import earth.worldwind.ogc.wmts.OWS11_PREFIX
import earth.worldwind.ogc.wmts.OwsBoundingBox
import earth.worldwind.ogc.wmts.OwsDescription
import earth.worldwind.ogc.wmts.OwsOnlineResource
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("FeatureType", WFS20_NAMESPACE, WFS20_PREFIX)
data class WfsFeatureType(
    @XmlElement
    @XmlSerialName("Name", WFS20_NAMESPACE, WFS20_PREFIX)
    val name: String,
    @XmlElement
    @XmlSerialName("Title", WFS20_NAMESPACE, WFS20_PREFIX)
    override val title: String? = null,
    @XmlElement
    @XmlSerialName("Abstract", WFS20_NAMESPACE, WFS20_PREFIX)
    override val abstract: String? = null,
    @XmlSerialName("Keywords", OWS11_NAMESPACE, OWS11_PREFIX)
    @XmlChildrenName("Keyword", OWS11_NAMESPACE, OWS11_PREFIX)
    override val keywords: List<String> = emptyList(),
    @XmlElement
    @XmlSerialName("DefaultCRS", WFS20_NAMESPACE, WFS20_PREFIX)
    val defaultCrs: String? = null,
    @XmlSerialName("OtherCRS", WFS20_NAMESPACE, WFS20_PREFIX)
    val otherCrs: List<String> = emptyList(),
    @XmlSerialName("WGS84BoundingBox", OWS11_NAMESPACE, OWS11_PREFIX)
    val wgs84BoundingBox: OwsBoundingBox? = null,
    @XmlSerialName("OutputFormats", WFS20_NAMESPACE, WFS20_PREFIX)
    @XmlChildrenName("Format", WFS20_NAMESPACE, WFS20_PREFIX)
    val outputFormats: List<String> = emptyList(),
    @XmlSerialName("MetadataURL", WFS20_NAMESPACE, WFS20_PREFIX)
    val metadataUrls: List<OwsOnlineResource> = emptyList(),
) : OwsDescription() {
    /** Supported CRS URIs for this feature type — [defaultCrs] first (when present),
     *  followed by [otherCrs] in their declared order. */
    val supportedCrs: List<String> get() = listOfNotNull(defaultCrs) + otherCrs

    @Transient
    lateinit var capabilities: WfsCapabilities
}
