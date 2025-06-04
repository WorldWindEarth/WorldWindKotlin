package earth.worldwind.formats.kml.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("innerBoundaryIs")
internal data class InnerBoundaryIs(var value: List<LinearRing>? = null) : Geometry()