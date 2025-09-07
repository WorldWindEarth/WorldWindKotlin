package earth.worldwind.formats.kml.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contains a [LinearRing] element.
 */
@Serializable
@SerialName("outerBoundaryIs")
internal data class OuterBoundaryIs(val value: LinearRing)