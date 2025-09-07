package earth.worldwind.formats.kml.models

import earth.worldwind.formats.kml.serializer.FlexibleBooleanSerializer
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Defines a connected set of line segments. Use [LineStyle] to specify the color, color mode, and width of the line.
 * When a [LineString] is extruded, the line is extended to the ground, forming a polygon that looks somewhat
 * like a wall or fence. For extruded [LineString]s, the line itself uses the current [LineStyle], and the extrusion
 * uses the current [PolyStyle]. See the KML Tutorial for examples of [LineString]s (or paths).
 */
@Serializable
internal data class LineString(
    override val id: String? = null,

    /**
     * A KML extension, in the Google extension namespace, that modifies how the altitude values are rendered.
     * This offset allows you to move an entire [LineString] up or down as a unit without modifying all the individual
     * coordinate values that make up the [LineString]. (Although the LineString is displayed using the altitude offset
     * value, the original altitude values are preserved in the KML file.) Units are in meters.
     */
    @XmlSerialName(prefix = "gx", value = "altitudeOffset")
    @XmlElement
    val altitudeOffset: Double = 0.0,

    /**
     * Boolean value. Specifies whether to connect the [LineString] to the ground. To extrude a [LineString],
     * the altitude mode must be either [AltitudeMode.relativeToGround], [AltitudeMode.relativeToSeaFloor],
     * or [AltitudeMode.absolute]. The vertices in the [LineString] are extruded toward the center of the Earth's sphere.
     */
    @Serializable(with = FlexibleBooleanSerializer::class)
    @XmlElement
    val extrude: Boolean = false,

    /**
     * Boolean value. Specifies whether to allow the [LineString] to follow the terrain. To enable tessellation,
     * the altitude mode must be [AltitudeMode.clampToGround] or [AltitudeMode.clampToSeaFloor].
     * Very large [LineString]s should enable tessellation so that they follow the curvature of the earth
     * (otherwise, they may go underground and be hidden).
     */
    @Serializable(with = FlexibleBooleanSerializer::class)
    @XmlElement
    val tessellate: Boolean = false,

    /**
     * Specifies how altitude components in the [coordinates] element are interpreted.
     */
    @XmlSerialName("altitudeMode")
    @XmlElement
    val altitudeMode: AltitudeMode = AltitudeMode.clampToGround,

    /**
     * An integer value that specifies the order for drawing multiple line strings. [LineString]s drawn first may be
     * partially or fully obscured by [LineString]s with a later draw order. This element may be required in conjunction
     * with the [LineStyle.outerColor] and [LineStyle.outerWidth] elements when dual-colored lines cross each other.
     */
    @XmlSerialName(prefix = "gx", value = "drawOrder")
    @XmlElement
    val drawOrder: Int = 0,

    /**
     * Two or more coordinate tuples, each consisting of floating point values for longitude, latitude, and altitude.
     * The altitude component is optional. Insert a space between tuples. Do not include spaces within a tuple.
     */
    @XmlSerialName("coordinates")
    @XmlElement
    val coordinates: Coordinates
) : Geometry()