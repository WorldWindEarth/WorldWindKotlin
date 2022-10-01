package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("HTTP", OWS11_NAMESPACE, OWS11_PREFIX)
data class OwsHttp(
    @XmlSerialName("Get", OWS11_NAMESPACE, OWS11_PREFIX)
    val getMethods: List<OwsHttpMethod> = emptyList(),
    @XmlSerialName("Post", OWS11_NAMESPACE, OWS11_PREFIX)
    val postMethods: List<OwsHttpMethod> = emptyList()
)