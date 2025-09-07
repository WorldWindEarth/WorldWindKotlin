package earth.worldwind.formats.kml.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contains a [LinearRing] element. A [Polygon] can contain multiple [InnerBoundaryIs] elements,
 * which create multiple cut-outs inside the [Polygon].
 */
@Serializable
@SerialName("innerBoundaryIs")
internal data class InnerBoundaryIs(val value: List<LinearRing> = emptyList())