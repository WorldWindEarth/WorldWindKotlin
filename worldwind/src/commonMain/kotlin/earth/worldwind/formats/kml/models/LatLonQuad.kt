package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Allows nonrectangular quadrilateral ground overlays.
 *
 * Specifies the coordinates of the four corner points of a quadrilateral defining the overlay area.
 * Exactly four coordinate tuples have to be provided, each consisting of floating point values for longitude and latitude.
 * Insert a space between tuples. Do not include spaces within a tuple. The coordinates must be specified in
 * counter-clockwise order with the first coordinate corresponding to the lower-left corner of the overlayed image.
 * The shape described by these corners must be convex.
 *
 * If a third value is inserted into any tuple (representing altitude) it will be ignored.
 * Altitude is set using [altitude] and [altitudeMode] extending [GroundOverlay].
 * Allowed altitude modes are [AltitudeMode.absolute], [AltitudeMode.clampToGround], and [AltitudeMode.clampToSeaFloor].
 */
@Serializable
internal data class LatLonQuad(
    /**
     * Four or more tuples, each consisting of floating point values for longitude, latitude, and altitude.
     * The altitude component is optional. Do not include spaces within a tuple.
     * The last coordinate must be the same as the first coordinate. Coordinates are expressed in decimal degrees only.
     */
    @XmlSerialName("coordinates")
    @XmlElement
    val coordinates: Coordinates
)