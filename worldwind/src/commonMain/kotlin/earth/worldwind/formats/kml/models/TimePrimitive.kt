package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable

/**
 * This is an abstract element and cannot be used directly in a KML file.
 * This element is extended by the [TimeSpan] and [TimeStamp] elements.
 */
@Serializable
internal abstract class TimePrimitive : Object()