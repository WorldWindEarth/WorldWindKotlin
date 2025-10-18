package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("ServiceContact", OWS11_NAMESPACE, OWS11_PREFIX)
data class OwsServiceContact(
    @XmlElement
    @XmlSerialName("IndividualName", OWS11_NAMESPACE, OWS11_PREFIX)
    val individualName: String? = null,
    @XmlElement
    @XmlSerialName("PositionName", OWS11_NAMESPACE, OWS11_PREFIX)
    val positionName: String? = null,
    val contactInfo: OwsContactInfo? = null,
    @XmlElement
    @XmlSerialName("Role", OWS11_NAMESPACE, OWS11_PREFIX)
    val role: String? = null,
)