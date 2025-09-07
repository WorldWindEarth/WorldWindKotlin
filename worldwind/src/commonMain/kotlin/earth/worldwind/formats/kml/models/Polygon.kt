package earth.worldwind.formats.kml.models

import earth.worldwind.formats.kml.serializer.FlexibleBooleanSerializer
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * A Polygon is defined by an outer boundary and 0 or more inner boundaries.
 * The boundaries, in turn, are defined by [LinearRing]s.
 * When a [Polygon] is extruded, its boundaries are connected to the ground to form additional polygons,
 * which gives the appearance of a building or a box.
 * Extruded Polygons use [PolyStyle] for their color, color mode, and fill.
 *
 * The <coordinates> for polygons must be specified in counterclockwise order.
 * Polygons follow the "right-hand rule," which states that if you place the fingers of your right hand in the direction
 * in which the coordinates are specified, your thumb points in the general direction of the geometric normal for the polygon.
 * (In 3D graphics, the geometric normal is used for lighting and points away from the front face of the polygon.)
 * Since Google Earth fills only the front face of polygons, you will achieve the desired effect only when the coordinates
 * are specified in the proper order. Otherwise, the polygon will be gray.
 */
@Serializable
internal data class Polygon(
    override val id: String? = null,

    /**
     * Boolean value. Specifies whether to connect the [Polygon] to the ground. To extrude a [Polygon], the altitude mode
     * must be either [AltitudeMode.relativeToGround], [AltitudeMode.relativeToSeaFloor], or [AltitudeMode.absolute].
     * Only the vertices are extruded, not the geometry itself (for example, a rectangle turns into a box with five faces).
     * The vertices of the Polygon are extruded toward the center of the Earth's sphere.
     */
    @Serializable(with = FlexibleBooleanSerializer::class)
    @XmlElement
    val extrude: Boolean = false,

    /**
     * This field is not used by [Polygon]. To allow a [Polygon] to follow the terrain (that is, to enable tessellation)
     * specify an altitude mode of [AltitudeMode.clampToGround] or [AltitudeMode.clampToSeaFloor].
     */
    @Deprecated("This field is not used by Polygon")
    @Serializable(with = FlexibleBooleanSerializer::class)
    @XmlElement
    val tessellate: Boolean = false,

    /**
     * Specifies how altitude components in the <coordinates> element are interpreted.
     */
    @XmlSerialName("altitudeMode")
    @XmlElement
    val altitudeMode: AltitudeMode = AltitudeMode.clampToGround,

    /**
     * Contains a [LinearRing] element.
     */
    @XmlElement
    val outerBoundaryIs: OuterBoundaryIs? = null,

    /**
     * Contains a [LinearRing] element. A Polygon can contain multiple [innerBoundaryIs] elements,
     * which create multiple cut-outs inside the Polygon.
     */
    @XmlElement
    val innerBoundaryIs: InnerBoundaryIs? = null
) : Geometry()