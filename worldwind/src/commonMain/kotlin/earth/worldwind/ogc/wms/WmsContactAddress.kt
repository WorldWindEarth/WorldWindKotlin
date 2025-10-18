package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("ContactAddress", WMS_NAMESPACE, WMS_PREFIX)
data class WmsContactAddress(
    @XmlElement
    @XmlSerialName("AddressType", WMS_NAMESPACE, WMS_PREFIX)
    val addressType: String,
    @XmlElement
    @XmlSerialName("Address", WMS_NAMESPACE, WMS_PREFIX)
    val address: String,
    @XmlElement
    @XmlSerialName("City", WMS_NAMESPACE, WMS_PREFIX)
    val city: String,
    @XmlElement
    @XmlSerialName("StateOrProvince", WMS_NAMESPACE, WMS_PREFIX)
    val stateOrProvince: String,
    @XmlElement
    @XmlSerialName("PostCode", WMS_NAMESPACE, WMS_PREFIX)
    val postCode: String,
    @XmlElement
    @XmlSerialName("Country", WMS_NAMESPACE, WMS_PREFIX)
    val country: String
)