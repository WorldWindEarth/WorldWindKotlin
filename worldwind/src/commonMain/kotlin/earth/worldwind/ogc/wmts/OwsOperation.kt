package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Operation", OWS11_NAMESPACE, OWS11_PREFIX)
data class OwsOperation(
    val name: String,
    val dcps: List<OwsDcp> = emptyList()
)