package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable

/**
 * This is an abstract element and cannot be used directly in a KML file.
 * It provides a placeholder object for all derived Geometry objects.
 */
@Serializable
abstract class Geometry : Object()