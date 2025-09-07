package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * A bounding box that describes an area of interest defined by geographic coordinates and altitudes.
 */
@Serializable
internal data class LatLonAltBox(
    /**
     * Possible values for [altitudeMode] are [AltitudeMode.clampToGround], [AltitudeMode.relativeToGround],
     * and [AltitudeMode.absolute], also [AltitudeMode.clampToSeaFloor] and [AltitudeMode.relativeToSeaFloor].
     * Also see [LatLonBox].
     */
    @XmlSerialName("altitudeMode")
    @XmlElement
    val altitudeMode: AltitudeMode = AltitudeMode.clampToGround,

    /**
     * Specified in meters (and is affected by the altitude mode specification).
     */
    @XmlElement
    val minAltitude: Double = 0.0,

    /**
     * Specified in meters (and is affected by the altitude mode specification).
     */
    @XmlElement
    val maxAltitude: Double = 0.0,

    /**
     * Specifies the latitude of the north edge of the bounding box, in decimal degrees from 0 to ±90.
     */
    @XmlElement
    val north: Double,

    /**
     * Specifies the latitude of the south edge of the bounding box, in decimal degrees from 0 to ±90.
     */
    @XmlElement
    val south: Double,

    /**
     * Specifies the longitude of the east edge of the bounding box, in decimal degrees from 0 to ±180.
     */
    @XmlElement
    val east: Double,

    /**
     * Specifies the longitude of the west edge of the bounding box, in decimal degrees from 0 to ±180.
     */
    @XmlElement
    val west: Double,
)