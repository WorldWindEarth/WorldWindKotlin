package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Style", WMTS10_NAMESPACE, WMTS10_PREFIX)
data class WmtsStyle(
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
    val isDefault: Boolean = false,
    val legendUrls: List<WmtsLegendURL> = emptyList()
): OwsDescription()