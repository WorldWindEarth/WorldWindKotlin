package earth.worldwind.formats.kml.models

import earth.worldwind.formats.kml.serializer.FlexibleBooleanSerializer
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Defines a closed line string, typically the outer boundary of a [Polygon]. Optionally, a [LinearRing] can also be
 * used as the inner boundary of a [Polygon] to create holes in the [Polygon]. A [Polygon] can contain multiple
 * [LinearRing] elements used as inner boundaries.
 */
@Serializable
internal data class LinearRing(
    override val id: String? = null,

    /**
     * A KML extension, in the Google extension namespace, that modifies how the altitude values are rendered.
     * This offset allows you to move an entire [LinearRing] up or down as a unit without modifying all the individual
     * coordinate values that make up the [LinearRing]. (Although the LinearRing is displayed using the altitude offset
     * value, the original altitude values are preserved in the KML file.) Units are in meters.
     */
    @XmlSerialName(prefix = "gx", value = "altitudeOffset")
    @XmlElement
    val altitudeOffset: Double = 0.0,

    /**
     * Boolean value. Specifies whether to connect the [LinearRing] to the ground. To extrude this geometry,
     * the altitude mode must be either [AltitudeMode.relativeToGround], [AltitudeMode.relativeToSeaFloor],
     * or [AltitudeMode.absolute]. Only the vertices of the [LinearRing] are extruded, not the center of the geometry.
     * The vertices are extruded toward the center of the Earth's sphere.
     */
    @Serializable(with = FlexibleBooleanSerializer::class)
    @XmlElement
    val extrude: Boolean = false,

    /**
     * Boolean value. Specifies whether to allow the [LinearRing] to follow the terrain. To enable tessellation,
     * the value for [altitudeMode] must be [AltitudeMode.clampToGround] or [AltitudeMode.clampToSeaFloor].
     * Very large [LinearRing]s should enable tessellation so that they follow the curvature of the earth (otherwise,
     * they may go underground and be hidden).
     */
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
     * Four or more tuples, each consisting of floating point values for longitude, latitude, and altitude.
     * The altitude component is optional. Do not include spaces within a tuple.
     * The last coordinate must be the same as the first coordinate. Coordinates are expressed in decimal degrees only.
     */
    @XmlSerialName("coordinates")
    @XmlElement
    val coordinates: Coordinates
) : Geometry()