package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Defines an image associated with an Icon style or overlay. The required <href> child element defines the location of
 * the image to be used as the overlay or as the icon for the placemark. This location can either be on a local file
 * system or a remote web server. The <gx:x>, <gx:y>, <gx:w>, and <gx:h> elements are used to select one icon from
 * an image that contains multiple icons (often referred to as an icon palette).
 */
@Serializable
internal data class Icon(
    override val id: String? = null,

    /**
     * An HTTP address or a local file specification used to load an icon.
     */
    @XmlElement
    val href: String? = null,

    /**
     * If the <href> specifies an icon palette, these elements identify the offsets, in pixels, from the lower-left
     * corner of the icon palette.If no values are specified for x and y, the lower left corner of the icon palette
     * is assumed to be the lower-left corner of the icon to use.
     */
    @XmlSerialName(prefix = "gx", value = "x")
    @XmlElement
    val x: Int = 0,

    /**
     * If the <href> specifies an icon palette, these elements identify the offsets, in pixels, from the lower-left
     * corner of the icon palette.If no values are specified for x and y, the lower left corner of the icon palette
     * is assumed to be the lower-left corner of the icon to use.
     */
    @XmlSerialName(prefix = "gx", value = "y")
    @XmlElement
    val y: Int = 0,

    /**
     * If the <href> specifies an icon palette, these elements specify the width (<gx:w>) and height (<gx:h>),
     * in pixels, of the icon to use.
     */
    @XmlSerialName(prefix = "gx", value = "w")
    @XmlElement
    val w: Int = 0,

    /**
     * If the <href> specifies an icon palette, these elements specify the width (<gx:w>) and height (<gx:h>),
     * in pixels, of the icon to use.
     */
    @XmlSerialName(prefix = "gx", value = "h")
    @XmlElement
    val h: Int = 0,

    /**
     * Specifies a time-based refresh mode
     */
    @XmlSerialName("refreshMode")
    @XmlElement
    val refreshMode: RefreshMode = RefreshMode.onChange,

    /**
     * Indicates to refresh the file every n seconds.
     */
    @XmlElement
    val refreshInterval: Int = 4,

    /**
     * Specifies how the link is refreshed when the "camera" changes.
     */
    @XmlSerialName("viewRefreshMode")
    @XmlElement
    val viewRefreshMode: ViewRefreshMode = ViewRefreshMode.never,

    /**
     * After camera movement stops, specifies the number of seconds to wait before refreshing the view.
     * (See [viewRefreshMode] and [ViewRefreshMode.onStop] above.)
     */
    @XmlElement
    val viewRefreshTime: Int = 4,

    /**
     * Scales the BBOX parameters before sending them to the server.
     * A value less than 1 specifies to use less than the full view (screen).
     * A value greater than 1 specifies to fetch an area that extends beyond the edges of the current view.
     */
    @XmlElement
    val viewBoundScale: Float = 1.0f,

    /**
     * Specifies the format of the query string that is appended to the Link's <href> before the file is fetched.
     * (If the <href> specifies a local file, this element is ignored.)
     */
    @XmlElement
    val viewFormat: String? = null,

    /**
     * Specifies the format of the query string that is appended to the Link's <href> before the file is fetched.
     * (If the <href> specifies a local file, this element is ignored.)
     */
    @XmlElement
    val httpQuery: String? = null,
) : Object()