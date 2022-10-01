package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("ServiceIdentification", OWS11_NAMESPACE, OWS11_PREFIX)
data class OwsServiceIdentification(
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
    @XmlSerialName("ServiceType", OWS11_NAMESPACE, OWS11_PREFIX)
    val serviceType: String,
    @XmlSerialName("ServiceTypeVersion", OWS11_NAMESPACE, OWS11_PREFIX)
    val serviceTypeVersions: List<String> = emptyList(),
    @XmlElement(true)
    @XmlSerialName("Fees", OWS11_NAMESPACE, OWS11_PREFIX)
    val fees: String? = null,
    @XmlSerialName("AccessConstraints", OWS11_NAMESPACE, OWS11_PREFIX)
    val accessConstraints: List<String> = emptyList(),
): OwsDescription()