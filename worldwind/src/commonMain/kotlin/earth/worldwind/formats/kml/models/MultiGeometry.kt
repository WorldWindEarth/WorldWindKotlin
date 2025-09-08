package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlPolyChildren

/**
 * A container for zero or more geometry primitives associated with the same feature.
 */
@Serializable
internal data class MultiGeometry(
    override val id: String? = null,

    /**
     * 0 or more [Geometry] elements
     */
    @XmlPolyChildren(
        [
            "earth.worldwind.formats.kml.models.Point",
            "earth.worldwind.formats.kml.models.LineString",
            "earth.worldwind.formats.kml.models.LinearRing",
            "earth.worldwind.formats.kml.models.Polygon",
        ]
    )
    @XmlElement
    val geometries: List<Geometry> = emptyList(),
) : Geometry()