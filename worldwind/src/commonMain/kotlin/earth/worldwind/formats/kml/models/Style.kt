package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlPolyChildren

/**
 * A Style defines an addressable style group that can be referenced by [StyleMap]s and [Feature]s.
 * Styles affect how [Geometry] is presented in the 3D viewer and how Features appear in the Places panel of the List view.
 * Shared styles are collected in a <Document> and must have an id defined for them so that they can be referenced
 * by the individual [Feature]s that use them.
 *
 * Use an id to refer to the style from a <styleUrl>.
 */
@Serializable
internal data class Style(
    override val id: String? = null,

    @XmlPolyChildren(
        [
            "earth.worldwind.formats.kml.models.LineStyle",
            "earth.worldwind.formats.kml.models.PolyStyle",
            "earth.worldwind.formats.kml.models.LabelStyle",
            "earth.worldwind.formats.kml.models.IconStyle",
        ]
    )
    @XmlElement
    val styles: List<ColorStyle> = emptyList(),
) : StyleSelector()