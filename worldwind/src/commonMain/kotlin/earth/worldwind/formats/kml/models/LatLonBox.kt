package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

/**
 * Specifies where the top, bottom, right, and left sides of a bounding box for the ground overlay are aligned.
 */
@Serializable
internal data class LatLonBox(
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
     * (For overlays that overlap the meridian of 180° longitude, values can extend beyond that range.)
     */
    @XmlElement
    val east: Double,

    /**
     * Specifies the longitude of the west edge of the bounding box, in decimal degrees from 0 to ±180.
     * (For overlays that overlap the meridian of 180° longitude, values can extend beyond that range.)
     */
    @XmlElement
    val west: Double,

    /**
     * Specifies a rotation of the overlay about its center, in degrees. Values can be ±180. The default is 0 (north).
     * Rotations are specified in a counterclockwise direction.
     */
    @XmlElement
    val rotation: Double = 0.0,
)