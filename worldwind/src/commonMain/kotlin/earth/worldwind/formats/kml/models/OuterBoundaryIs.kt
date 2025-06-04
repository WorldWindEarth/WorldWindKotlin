package earth.worldwind.formats.kml.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("outerBoundaryIs")
internal data class OuterBoundaryIs(var value: List<LinearRing>? = null) : Geometry()