package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Phone", OWS11_NAMESPACE, OWS11_PREFIX)
data class OwsPhone(
    @XmlElement(true)
    @XmlSerialName("Voice", OWS11_NAMESPACE, OWS11_PREFIX)
    val voice: List<String> = emptyList(),
    @XmlElement(true)
    @XmlSerialName("Facsimile", OWS11_NAMESPACE, OWS11_PREFIX)
    val fax: List<String> = emptyList()
)