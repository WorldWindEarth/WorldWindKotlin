package earth.worldwind.ogc.gml

import kotlinx.serialization.Serializable

@Serializable
data class GmlPointProperty(
    val points: List<GmlPoint> = emptyList(),
    var nilReason: String? = null
)