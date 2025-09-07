package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Defines the virtual camera that views the scene. This element defines the position of the camera relative to
 * the Earth's surface as well as the viewing direction of the camera. The camera position is defined by [longitude],
 * [latitude], [altitude], and either [altitudeMode]. The viewing direction of the camera is defined by [heading],
 * [tilt], and [roll]. [Camera] can be a child element of any Feature or of [NetworkLinkControl].
 * A parent element cannot contain both a [Camera] and a [LookAt] at the same time.
 *
 * [Camera] provides full six-degrees-of-freedom control over the view, so you can position the Camera in space and
 * then rotate it around the X, Y, and Z axes. Most importantly, you can tilt the camera view so that you're looking
 * above the horizon into the sky.
 *
 * [Camera] can also contain a [TimePrimitive]. Time values in Camera affect historical imagery, sunlight,
 * and the display of time-stamped features. For more information, read Time with [AbstractView]s in
 * the Time and Animation chapter of the Developer's Guide.
 */
@Serializable
internal data class Camera(
    /**
     * Longitude of the virtual camera (eye point). Angular distance in degrees, relative to the Prime Meridian.
     * Values west of the Meridian range from −180 to 0 degrees. Values east of the Meridian range from 0 to 180 degrees.
     */
    @XmlElement
    val longitude: Double = 0.0,

    /**
     * Latitude of the virtual camera. Degrees north or south of the Equator (0 degrees).
     * Values range from −90 degrees to 90 degrees
     */
    @XmlElement
    val latitude: Double = 0.0,

    /**
     * Distance of the camera from the earth's surface, in meters.
     * Interpreted according to the Camera's [altitudeMode].
     */
    @XmlElement
    val altitude: Double = 0.0,

    /**
     * Direction (azimuth) of the camera, in degrees. Default=0 (true North). Values range from 0 to 360 degrees.
     */
    @XmlElement
    val heading: Double = 0.0,

    /**
     * Rotation, in degrees, of the camera around the X axis. A value of 0 indicates that the view is aimed straight
     * down toward the earth (the most common case). A value for 90 for [tilt] indicates that the view is aimed toward
     * the horizon. Values greater than 90 indicate that the view is pointed up into the sky.
     * Values for [tilt] are clamped at +180 degrees.
     */
    @XmlElement
    val tilt: Double = 0.0,

    /**
     * Rotation, in degrees, of the camera around the Z axis. Values range from −180 to +180 degrees.
     */
    @XmlElement
    val roll: Double = 0.0,

    /**
     * Specifies how the <altitude> specified for the Camera is interpreted.
     */
    @XmlSerialName("altitudeMode")
    @XmlElement
    val altitudeMode: AltitudeMode = AltitudeMode.relativeToGround
): AbstractView()