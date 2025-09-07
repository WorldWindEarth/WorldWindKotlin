package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlPolyChildren

/**
 * This is an abstract element and cannot be used directly in a KML file.
 * A [Container] element holds one or more [Feature]s and allows the creation of nested hierarchies.
 */
@Serializable
internal abstract class Container(
    /**
     * List of [Feature]s included in the [Container].
     */
    @XmlPolyChildren(
        [
            "earth.worldwind.formats.kml.models.Document",
            "earth.worldwind.formats.kml.models.Folder",
            "earth.worldwind.formats.kml.models.Placemark",
            "earth.worldwind.formats.kml.models.GroundOverlay",
        ]
    )
    val features: List<Feature> = emptyList(),
) : Feature()