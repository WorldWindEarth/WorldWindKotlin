package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("ContactInfo", OWS11_NAMESPACE, OWS11_PREFIX)
data class OwsContactInfo(
    val phone: OwsPhone? = null,
    val address: OwsAddress? = null,
    val onlineResource: OwsOnlineResource? = null,
    @XmlElement(true)
    @XmlSerialName("HoursOfService", OWS11_NAMESPACE, OWS11_PREFIX)
    val hoursOfService: String? = null,
    @XmlElement(true)
    @XmlSerialName("ContactInstructions", OWS11_NAMESPACE, OWS11_PREFIX)
    val contactInstructions: String? = null
)