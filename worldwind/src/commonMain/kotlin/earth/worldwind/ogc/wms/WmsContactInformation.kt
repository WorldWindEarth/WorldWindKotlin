package earth.worldwind.ogc.wms

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("ContactInformation", WMS_NAMESPACE, WMS_PREFIX)
data class WmsContactInformation(
    @XmlElement(true)
    @XmlSerialName("ContactPosition", WMS_NAMESPACE, WMS_PREFIX)
    val position: String? = null,
    @XmlElement(true)
    @XmlSerialName("ContactVoiceTelephone", WMS_NAMESPACE, WMS_PREFIX)
    val voiceTelephone: String? = null,
    @XmlElement(true)
    @XmlSerialName("ContactFacsimileNumber", WMS_NAMESPACE, WMS_PREFIX)
    val facsimileTelephone: String? = null,
    @XmlElement(true)
    @XmlSerialName("ContactElectronicMailAddress", WMS_NAMESPACE, WMS_PREFIX)
    val electronicMailAddress: String? = null,
    val contactAddress: WmsContactAddress? = null,
    val contactPersonPrimary: WmsContactPersonPrimary? = null
)