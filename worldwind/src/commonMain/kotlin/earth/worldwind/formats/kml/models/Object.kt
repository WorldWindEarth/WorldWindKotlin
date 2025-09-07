package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable

@Serializable
internal abstract class Object : AbstractKml() {
    abstract val id: String?
}