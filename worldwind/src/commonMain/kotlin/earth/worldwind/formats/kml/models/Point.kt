package earth.worldwind.formats.kml.models

import earth.worldwind.formats.kml.serializer.FlexibleBooleanSerializer
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * A geographic location defined by longitude, latitude, and (optional) altitude. When a [Point] is contained by
 * a [Placemark], the point itself determines the position of the [Placemark]'s name and icon. When a [Point] is extruded,
 * it is connected to the ground with a line. This "tether" uses the current [LineStyle].
 */
@Serializable
internal data class Point(
    override val id: String? = null,

    /**
     * Boolean value. Specifies whether to connect the point to the ground with a line. To extrude a [Point],
     * the value for [altitudeMode] must be either [AltitudeMode.relativeToGround], [AltitudeMode.relativeToSeaFloor],
     * or [AltitudeMode.absolute]. The point is extruded toward the center of the Earth's sphere.
     */
    @XmlElement
    @Serializable(FlexibleBooleanSerializer::class)
    val extrude: Boolean = false,

    /**
     * Specifies how altitude components in the <coordinates> element are interpreted.
     */
    @XmlSerialName("altitudeMode")
    @XmlElement
    val altitudeMode: AltitudeMode = AltitudeMode.clampToGround,

    /**
     * A single tuple consisting of floating point values for longitude, latitude, and altitude (in that order).
     * Longitude and latitude values are in degrees, where
     * - longitude ≥ −180 and <= 180
     * - latitude ≥ −90 and ≤ 90
     * - altitude values (optional) are in meters above sea level
     * Do not include spaces between the three values that describe a coordinate.
     */
    @XmlSerialName("coordinates")
    @XmlElement
    val coordinates: Coordinate,
) : Geometry()