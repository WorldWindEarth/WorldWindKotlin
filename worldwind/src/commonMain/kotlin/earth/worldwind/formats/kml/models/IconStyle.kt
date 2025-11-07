package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Specifies how icons for point [Placemark]s are drawn, both in the Places panel and in the 3D viewer of Google Earth.
 * The [Icon] element specifies the icon image. The [scale] element specifies the x, y scaling of the icon.
 * The color specified in the [color] element of [IconStyle] is blended with the color of the [Icon].
 */
@Serializable
internal data class IconStyle(
    override val id: String? = null,

    @XmlElement
    override var color: String = "ffffffff",

    @XmlElement
    override val colorMode: ColorMode = ColorMode.normal,

    /**
     * Resizes the icon.
     */
    @XmlElement
    val scale: Double = 1.0,

    /**
     * Direction (that is, North, South, East, West), in degrees. Default=0 (North). Values range from 0 to 360 degrees.
     */
    @XmlElement
    val heading: Double = 0.0,

    /**
     * A custom Icon. In <IconStyle>, the only child element of <Icon> is <href>:
     * <href>: An HTTP address or a local file specification used to load an icon.
     */
    @XmlElement
    val icon: Icon? = null,

    /**
     * Specifies the position within the Icon that is "anchored" to the <Point> specified in the Placemark.
     */
    @XmlSerialName("hotSpot")
    @XmlElement
    val hotSpot: HotSpot? = null
) : ColorStyle()