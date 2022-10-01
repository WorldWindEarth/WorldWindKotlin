package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("ServiceContact", OWS11_NAMESPACE, OWS11_PREFIX)
data class OwsServiceContact(
    @XmlElement(true)
    @XmlSerialName("IndividualName", OWS11_NAMESPACE, OWS11_PREFIX)
    val individualName: String? = null,
    @XmlElement(true)
    @XmlSerialName("PositionName", OWS11_NAMESPACE, OWS11_PREFIX)
    val positionName: String? = null,
    val contactInfo: OwsContactInfo? = null,
    @XmlElement(true)
    @XmlSerialName("Role", OWS11_NAMESPACE, OWS11_PREFIX)
    val role: String? = null,
)