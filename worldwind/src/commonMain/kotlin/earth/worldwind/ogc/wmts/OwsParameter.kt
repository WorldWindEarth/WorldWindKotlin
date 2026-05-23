package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Parameter", OWS11_NAMESPACE, OWS11_PREFIX)
data class OwsParameter(
    val name: String,
    @XmlSerialName("AllowedValues", OWS11_NAMESPACE, OWS11_PREFIX)
    @XmlChildrenName("Value", OWS11_NAMESPACE, OWS11_PREFIX)
    val allowedValues: List<String> = emptyList()
)
