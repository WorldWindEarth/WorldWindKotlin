package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Defines a virtual camera that is associated with any element derived from Feature.
 * The LookAt element positions the "camera" in relation to the object that is being viewed.
 * In Google Earth, the view "flies to" this LookAt viewpoint when the user double-clicks an item in the Places panel
 * or double-clicks an icon in the 3D viewer.
 */
@Serializable
internal data class LookAt(
    /**
     * Longitude of the point the camera is looking at. Angular distance in degrees, relative to the Prime Meridian.
     * Values west of the Meridian range from −180 to 0 degrees. Values east of the Meridian range from 0 to 180 degrees.
     */
    @XmlElement
    val longitude: Double = 0.0,

    /**
     * Latitude of the point the camera is looking at. Degrees north or south of the Equator (0 degrees).
     * Values range from −90 degrees to 90 degrees.
     */
    @XmlElement
    val latitude: Double = 0.0,

    /**
     * Distance from the earth's surface, in meters. Interpreted according to the LookAt's altitude mode.
     */
    @XmlElement
    val altitude: Double = 0.0,

    /**
     * Direction (that is, North, South, East, West), in degrees. Default=0 (North).
     * Values range from 0 to 360 degrees.
     */
    @XmlElement
    val heading: Double = 0.0,

    /**
     * Angle between the direction of the LookAt position and the normal to the surface of the earth.
     * Values range from 0 to 90 degrees. Values for [tilt] cannot be negative.
     * A [tilt] value of 0 degrees indicates viewing from directly above.
     * A [tilt] value of 90 degrees indicates viewing along the horizon.
     */
    @XmlElement
    val tilt: Double = 0.0,

    /**
     * Distance in meters from the point specified by [longitude], [latitude], and [altitude] to the LookAt position.
     */
    @XmlElement
    val range: Double,

    /**
     * Specifies how the <altitude> specified for the LookAt point is interpreted.
     */
    @XmlSerialName("altitudeMode")
    @XmlElement
    val altitudeMode: AltitudeMode = AltitudeMode.clampToGround
): AbstractView()