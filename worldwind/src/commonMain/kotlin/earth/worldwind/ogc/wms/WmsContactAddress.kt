package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("ContactAddress", WMS_NAMESPACE, WMS_PREFIX)
data class WmsContactAddress(
    @XmlElement(true)
    @XmlSerialName("AddressType", WMS_NAMESPACE, WMS_PREFIX)
    val addressType: String,
    @XmlElement(true)
    @XmlSerialName("Address", WMS_NAMESPACE, WMS_PREFIX)
    val address: String,
    @XmlElement(true)
    @XmlSerialName("City", WMS_NAMESPACE, WMS_PREFIX)
    val city: String,
    @XmlElement(true)
    @XmlSerialName("StateOrProvince", WMS_NAMESPACE, WMS_PREFIX)
    val stateOrProvince: String,
    @XmlElement(true)
    @XmlSerialName("PostCode", WMS_NAMESPACE, WMS_PREFIX)
    val postCode: String,
    @XmlElement(true)
    @XmlSerialName("Country", WMS_NAMESPACE, WMS_PREFIX)
    val country: String
)