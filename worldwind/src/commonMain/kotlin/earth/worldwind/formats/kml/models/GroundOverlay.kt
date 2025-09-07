package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * This element draws an image overlay draped onto the terrain. The <href> child of [Icon] specifies the image to be
 * used as the overlay. This file can be either on a local file system or on a web server. If this element is omitted
 * or contains no <href>, a rectangle is drawn using the color and LatLonBox bounds defined by the ground overlay.
 */
@Serializable
internal data class GroundOverlay(
    override val id: String? = null,

    /**
     * Specifies the distance above the earth's surface, in meters, and is interpreted according to the altitude mode.
     */
    @XmlElement
    val altitude: Double = 0.0,

    /**
     * Specifies how the paltitude] is interpreted.
     */
    @XmlSerialName("altitudeMode")
    @XmlElement
    val altitudeMode: AltitudeMode = AltitudeMode.clampToGround,

    /**
     * Specifies where the top, bottom, right, and left sides of a bounding box for the ground overlay are aligned.
     */
    @XmlElement
    val latLonBox: LatLonBox? = null,

    /**
     * Used for nonrectangular quadrilateral ground overlays.
     */
    @XmlSerialName(prefix = "gx", value = "latLonQuad")
    @XmlElement
    val latLonQuad: LatLonQuad? = null,
) : Overlay()