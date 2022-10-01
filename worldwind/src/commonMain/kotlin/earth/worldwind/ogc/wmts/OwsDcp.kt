package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("DCP", OWS11_NAMESPACE, OWS11_PREFIX)
data class OwsDcp(
    val http: OwsHttp
) {
    val getMethods get() = http.getMethods
    val postMethods get() = http.postMethods
}