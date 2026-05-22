package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable

@Serializable
abstract class Object {
    abstract val id: String?
}