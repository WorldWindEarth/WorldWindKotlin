package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlValue

/**
 * A short description of the feature. In Google Earth, this description is displayed in the Places panel under
 * the name of the feature. If a [Snippet] is not supplied, the first two lines of the [description] are used.
 * In Google Earth, if a Placemark contains both a description and a Snippet, the <Snippet> appears beneath the Placemark in the Places panel, and the <description> appears in the Placemark's description balloon. This tag does not support HTML markup. <Snippet> has a maxLines attribute, an integer that specifies the maximum number of lines to display.
 */
@Serializable
internal data class Snippet(
    @XmlValue(true) val value: String,
    val maxLines: Int = 2,
) : AbstractKml() {
    override fun toString() = value
}