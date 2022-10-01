package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlDefault
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Constraint", OWS11_NAMESPACE, OWS11_PREFIX)
data class OwsConstraint(
    val name: String,
    @XmlSerialName("AllowedValues", OWS11_NAMESPACE, OWS11_PREFIX)
    @XmlChildrenName("Value", OWS11_NAMESPACE, OWS11_PREFIX)
    @XmlDefault("AnyValue")
    val allowedValues: List<String> = emptyList()
)