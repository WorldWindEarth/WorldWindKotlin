package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlPolyChildren

@Serializable
internal data class Style(
    val id: String? = null,
    @XmlPolyChildren(
        [
            "earth.worldwind.formats.kml.models.LineStyle",
            "earth.worldwind.formats.kml.models.PolyStyle",
            "earth.worldwind.formats.kml.models.LabelStyle",
            "earth.worldwind.formats.kml.models.IconStyle",
        ]
    )
    @XmlElement
    val stylesList: List<AbstractStyle> = emptyList(),
) : AbstractKml()
