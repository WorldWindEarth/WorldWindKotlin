package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable

/**
 * This is an abstract element and cannot be used directly in a KML file. It is the base type for the [Style]
 * and [StyleMap] elements. The StyleMap element selects a style based on the current mode of the [Placemark].
 * An element derived from StyleSelector is uniquely identified by its id and its url.
 */
@Serializable
internal abstract class StyleSelector : Object()