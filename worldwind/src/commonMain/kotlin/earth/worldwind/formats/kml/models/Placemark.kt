package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlPolyChildren

/**
 * A Placemark is a Feature with associated Geometry. In Google Earth, a Placemark appears as a list item in the Places panel.
 * A Placemark with a Point has an icon associated with it that marks a point on the Earth in the 3D viewer.
 * (In the Google Earth 3D viewer, a Point Placemark is the only object you can click or roll over.
 * Other Geometry objects do not have an icon in the 3D viewer. To give the user something to click in the 3D viewer,
 * you would need to create a MultiGeometry object that contains both a Point and the other Geometry object.)
 */
@Serializable
internal data class Placemark(
    override val id: String? = null,

    /**
     * 0 or one <Geometry> elements
     */
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
    val geometryList: List<Geometry>? = null,
) : Feature()
