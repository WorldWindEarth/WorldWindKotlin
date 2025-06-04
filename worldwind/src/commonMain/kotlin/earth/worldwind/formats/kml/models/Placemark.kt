package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlPolyChildren

@Serializable
internal data class Placemark(
    @XmlElement
    var styleUrl: String? = null,
    @XmlElement
    var description: String? = null,
    @XmlElement
    var stylesList: List<Style>? = null,
    @XmlPolyChildren(
        [
            "earth.worldwind.formats.kml.models.Point",
            "earth.worldwind.formats.kml.models.LineString",
            "earth.worldwind.formats.kml.models.MultiGeometry",
            "earth.worldwind.formats.kml.models.Polygon",
            "earth.worldwind.formats.kml.models.LinearRing",
        ]
    )
    @XmlElement
    var geometryList: List<Geometry>? = null,
) : Feature()
