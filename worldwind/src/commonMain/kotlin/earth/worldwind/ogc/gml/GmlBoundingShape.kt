package earth.worldwind.ogc.gml

import kotlinx.serialization.Serializable

@Serializable
data class GmlBoundingShape(
    val envelope: GmlEnvelope,
    val nilReason: String? = null
)