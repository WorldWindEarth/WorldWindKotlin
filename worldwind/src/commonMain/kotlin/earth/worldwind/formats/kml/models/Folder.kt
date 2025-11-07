package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable

/**
 * A Folder is used to arrange other Features hierarchically ([Folder]s, [Placemark]s, [NetworkLink]s, or [Overlay]s).
 * A [Feature] is visible only if it and all its ancestors are visible.
 */
@Serializable
internal data class Folder(override val id: String? = null) : Container()