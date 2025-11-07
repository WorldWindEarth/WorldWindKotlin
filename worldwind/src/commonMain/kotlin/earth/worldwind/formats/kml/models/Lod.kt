package earth.worldwind.formats.kml.models

import earth.worldwind.formats.kml.serializer.FlexibleIntSerializer
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

/**
 * [Lod] is an abbreviation for Level of Detail. [Lod] describes the size of the projected region on the screen that is
 * required in order for the region to be considered "active." Also specifies the size of the pixel ramp used for fading
 * in (from transparent to opaque) and fading out (from opaque to transparent).
 * See diagram below for a visual representation of these parameters.
 */
@Serializable
internal data class Lod(
    /**
     * Defines a square in screen space, with sides of the specified value in pixels. For example, 128 defines a square of 128 x 128 pixels. The region's bounding box must be larger than this square (and smaller than the maxLodPixels square) in order for the Region to be active.
     *
     * More details are available in the Working with Regions chapter of the Developer's Guide, as well as the Google Earth Outreach documentation's Avoiding Overload with Regions tutorial.
     *
     */
    @Serializable(FlexibleIntSerializer::class)
    @XmlElement
    val minLodPixels: Int,

    /**
     * Measurement in screen pixels that represents the maximum limit of the visibility range for a given Region.
     * A value of −1, the default, indicates "active to infinite size."
     */

    @Serializable(FlexibleIntSerializer::class)
    @XmlElement
    val maxLodPixels: Int = -1,

    /**
     * Distance over which the geometry fades, from fully opaque to fully transparent. This ramp value, expressed
     * in screen pixels, is applied at the minimum end of the LOD (visibility) limits.
     */
    @XmlElement
    val minFadeExtent: Int = 0,

    /**
     * Distance over which the geometry fades, from fully transparent to fully opaque. This ramp value, expressed
     * in screen pixels, is applied at the maximum end of the LOD (visibility) limits.
     */
    @XmlElement
    val maxFadeExtent: Int = 0,
)