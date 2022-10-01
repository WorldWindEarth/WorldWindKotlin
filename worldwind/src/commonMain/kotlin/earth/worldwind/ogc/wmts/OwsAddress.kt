package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Address", OWS11_NAMESPACE, OWS11_PREFIX)
data class OwsAddress(
    @XmlSerialName("DeliveryPoint", OWS11_NAMESPACE, OWS11_PREFIX)
    val deliveryPoints: List<String> = emptyList(),
    @XmlElement(true)
    @XmlSerialName("City", OWS11_NAMESPACE, OWS11_PREFIX)
    val city: String? = null,
    @XmlElement(true)
    @XmlSerialName("AdministrativeArea", OWS11_NAMESPACE, OWS11_PREFIX)
    val administrativeArea: String? = null,
    @XmlElement(true)
    @XmlSerialName("PostalCode", OWS11_NAMESPACE, OWS11_PREFIX)
    val postalCode: String? = null,
    @XmlElement(true)
    @XmlSerialName("Country", OWS11_NAMESPACE, OWS11_PREFIX)
    val country: String? = null,
    @XmlSerialName("ElectronicMailAddress", OWS11_NAMESPACE, OWS11_PREFIX)
    val electronicMailAddresses: List<String> = emptyList()
)