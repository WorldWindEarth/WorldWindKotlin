package earth.worldwind.ogc.gml

import kotlinx.serialization.Serializable

@Serializable
data class GmlGridLimits(
    val gridEnvelope: GmlGridEnvelope
)