package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

/**
 * A [StyleMap] maps between two different Styles. Typically a [StyleMap] element is used to provide separate normal and
 * highlighted styles for a placemark, so that the highlighted version appears when the user mouses over the icon in Google Earth.
 */
@Serializable
internal data class StyleMap(
    override val id: String? = null,

    @XmlElement
    val pairs: List<StylePair>,
) : StyleSelector()