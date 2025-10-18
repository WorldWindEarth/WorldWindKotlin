package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Theme", WMTS10_NAMESPACE, WMTS10_PREFIX)
data class WmtsTheme(
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
    val themes: List<WmtsTheme> = emptyList(),
    @XmlSerialName("LayerRef", WMTS10_NAMESPACE, WMTS10_PREFIX)
    val layerRefs: List<String> = emptyList()
): OwsDescription()