package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("ContactPersonPrimary", WMS_NAMESPACE, WMS_PREFIX)
data class WmsContactPersonPrimary(
    @XmlElement
    @XmlSerialName("ContactPerson", WMS_NAMESPACE, WMS_PREFIX)
    val contactPerson: String,
    @XmlElement
    @XmlSerialName("ContactOrganization", WMS_NAMESPACE, WMS_PREFIX)
    val contactOrganization: String
)