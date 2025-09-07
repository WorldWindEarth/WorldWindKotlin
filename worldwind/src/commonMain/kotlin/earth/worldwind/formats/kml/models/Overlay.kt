package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

/**
 * This is an abstract element and cannot be used directly in a KML file. <Overlay> is the base type for image overlays
 * drawn on the planet surface or on the screen. <Icon> specifies the image to use and can be configured to reload
 * images based on a timer or by camera changes. This element also includes specifications for stacking order of
 * multiple overlays and for adding color and transparency values to the base image.
 */
@Serializable
internal abstract class Overlay(
    /**
     * Color values are expressed in hexadecimal notation, including opacity (alpha) values. The order of expression
     * is alpha, blue, green, red (aabbggrr). The range of values for any one color is 0 to 255 (00 to ff).
     * For opacity, 00 is fully transparent and ff is fully opaque. For example, if you want to apply a blue color
     * with 50 percent opacity to an overlay, you would specify the following: <color>7fff0000</color>
     */
    @XmlElement
    val color: String? = null,

    /**
     * This element defines the stacking order for the images in overlapping overlays.
     * Overlays with higher [drawOrder] values are drawn on top of overlays with lower [drawOrder] values.
     */
    @XmlElement
    val drawOrder: Int = 0,

    /**
     * Defines the image associated with the [Overlay]. The <href> element defines the location of the image to be used
     * as the [Overlay]. This location can be either on a local file system or on a web server. If this element is omitted
     * or contains no <href>, a rectangle is drawn using the color and size defined by the ground or screen overlay.
     */
    @XmlElement
    val icon: Icon? = null,
) : Feature()