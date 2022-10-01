package earth.worldwind.ogc.wmts

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("OperationsMetadata", OWS11_NAMESPACE, OWS11_PREFIX)
data class OwsOperationsMetadata(
    val operations: List<OwsOperation> = emptyList()
) {
    val getCapabilities get() = operations.firstOrNull { operation -> operation.name == "GetCapabilities" }
    val getTile get() = operations.firstOrNull { operation -> operation.name == "GetTile" }
}