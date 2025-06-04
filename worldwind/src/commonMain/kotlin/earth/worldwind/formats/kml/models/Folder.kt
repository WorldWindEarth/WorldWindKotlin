package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlPolyChildren

@Serializable
internal data class Folder(
    @XmlElement
    val styles: List<Style>? = null,
    @XmlElement
    val styleMaps: List<StyleMap>? = null,
    @XmlPolyChildren(
        [
            "earth.worldwind.formats.kml.models.Placemark",
            "earth.worldwind.formats.kml.models.Folder",
        ]
    )
    var features: List<Feature>? = null
) : Feature()