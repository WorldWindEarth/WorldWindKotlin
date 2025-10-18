package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("ContactInformation", WMS_NAMESPACE, WMS_PREFIX)
data class WmsContactInformation(
    @XmlElement
    @XmlSerialName("ContactPosition", WMS_NAMESPACE, WMS_PREFIX)
    val position: String? = null,
    @XmlElement
    @XmlSerialName("ContactVoiceTelephone", WMS_NAMESPACE, WMS_PREFIX)
    val voiceTelephone: String? = null,
    @XmlElement
    @XmlSerialName("ContactFacsimileNumber", WMS_NAMESPACE, WMS_PREFIX)
    val facsimileTelephone: String? = null,
    @XmlElement
    @XmlSerialName("ContactElectronicMailAddress", WMS_NAMESPACE, WMS_PREFIX)
    val electronicMailAddress: String? = null,
    val contactAddress: WmsContactAddress? = null,
    val contactPersonPrimary: WmsContactPersonPrimary? = null
)