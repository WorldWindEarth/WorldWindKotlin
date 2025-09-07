package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Specifies the position within the Icon that is "anchored" to the <Point> specified in the Placemark.
 * The x and y values can be specified in three different ways: as pixels ("pixels"), as fractions of the icon ("fraction"),
 * or as inset pixels ("insetPixels"), which is an offset in pixels from the upper right corner of the icon.
 * The x and y positions can be specified in different ways—for example, x can be in pixels and y can be a fraction.
 * The origin of the coordinate system is in the lower left corner of the icon.
 */
@Serializable
internal data class HotSpot(
    /**
     * Either the number of pixels, a fractional component of the icon, or a pixel inset indicating the x component of a point on the icon.
     */
    val x: Double,

    /**
     * Either the number of pixels, a fractional component of the icon, or a pixel inset indicating the y component of a point on the icon.
     */
    val y: Double,

    /**
     * Units in which the x value is specified. A value of fraction indicates the x value is a fraction of the icon.
     * A value of pixels indicates the x value in pixels. A value of insetPixels indicates the indent from the right edge of the icon.
     */
    @XmlSerialName("xunits")
    val xunits: HotSpotUnits,

    /**
     * Units in which the y value is specified. A value of fraction indicates the y value is a fraction of the icon.
     * A value of pixels indicates the y value in pixels. A value of insetPixels indicates the indent from the top edge of the icon.
     */
    @XmlSerialName("yunits")
    val yunits: HotSpotUnits,
)