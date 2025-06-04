package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlPolyChildren

@Serializable
internal data class MultiGeometry(
    @XmlPolyChildren(
        [
            "earth.worldwind.formats.kml.models.Point",
            "earth.worldwind.formats.kml.models.LineString",
            "earth.worldwind.formats.kml.models.LinearRing",
            "earth.worldwind.formats.kml.models.Polygon",
        ]
    )
    @XmlElement
    var geometryList: List<Geometry>? = null,
) : Geometry()