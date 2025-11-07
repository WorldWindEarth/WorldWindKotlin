package earth.worldwind.formats.kml.models

import earth.worldwind.formats.kml.serializer.FlexibleBooleanSerializer
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlPolyChildren

/**
 * This is an abstract element and cannot be used directly in a KML file.
 * The following diagram shows how some of a [Feature]'s elements appear in Google Earth.
 */
@Serializable
internal abstract class Feature(
    /**
     * User-defined text displayed in the 3D viewer as the label for the object
     * (for example, for a [Placemark], [Folder], or [NetworkLink]).
     */
    @XmlElement
    val name: String? = null,

    /**
     * Boolean value. Specifies whether the feature is drawn in the 3D viewer when it is initially loaded.
     * In order for a feature to be visible, the [visibility] tag of all its ancestors must also be set to 1.
     * In the Google Earth List View, each Feature has a checkbox that allows the user to control
     * visibility of the [Feature].
     */
    @XmlElement
    @Serializable(FlexibleBooleanSerializer::class)
    val visibility: Boolean = true,

    /**
     * Boolean value. Specifies whether a Document or Folder appears closed or open when first loaded into the
     * Places panel. 0=collapsed (the default), 1=expanded. See also [ListStyle].
     * This element applies only to [Document], [Folder], and [NetworkLink].
     */
    @XmlElement
    @Serializable(FlexibleBooleanSerializer::class)
    val open: Boolean = false,

    /**
     * A string value representing an unstructured address written as a standard street, city, state address, and/or
     * as a postal code. You can use the [address] tag to specify the location of a point instead of using latitude and
     * longitude coordinates. (However, if a [Point] is provided, it takes precedence over the [address].)
     * To find out which locales are supported for this tag in Google Earth, go to the Google Maps Help.
     */
    @XmlElement
    val address: String? = null,

    /**
     * A string value representing a telephone number. This element is used by Google Maps Mobile only.
     * The industry standard for Java-enabled cellular phones is RFC2806.
     * For more information, see http://www.ietf.org/rfc/rfc2806.txt.
     */
    @XmlElement
    val phoneNumber: String? = null,

    /**
     * A short description of the feature.
     */
    @XmlElement
    val snippet: Snippet? = null,

    /**
     * User-supplied content that appears in the description balloon.
     */
    @XmlElement
    val description: String? = null,

    /**
     * Defines a viewpoint associated with any element derived from Feature. See [Camera] and [LookAt].
     */
    @XmlPolyChildren(
        [
            "earth.worldwind.formats.kml.models.Camera",
            "earth.worldwind.formats.kml.models.LookAt",
        ]
    )
    val abstractView: AbstractView? = null,

    /**
     * Associates this Feature with a period of time [TimeSpan] or a point in time [TimeStamp].
     */
    @XmlPolyChildren(
        [
            "earth.worldwind.formats.kml.models.TimeSpan",
            "earth.worldwind.formats.kml.models.TimeStamp",
        ]
    )
    val timePrimitive: TimePrimitive? = null,

    /**
     * URL of a [Style] or [StyleMap] defined in a [Document]. If the style is in the same file, use a # reference.
     * If the style is defined in an external file, use a full URL along with # referencing.
     */
    @XmlElement
    val styleUrl: String? = null,

    /**
     * One or more [Style]s and [StyleMap]s can be defined to customize the appearance of any element derived from [Feature]
     * or of the Geometry in a [Placemark]. (See [BalloonStyle], [ListStyle], [StyleSelector],
     * and the styles derived from [ColorStyle].) A style defined within a Feature is called an "inline style"
     * and applies only to the Feature that contains it. A style defined as the child of a [Document] is called
     * a "shared style." A shared style must have and id defined for it. This id is referenced by one or more Features
     * within the [Document]. In cases where a style element is defined both in a shared style and in an inline style
     * for a [Feature]—that is, a [Folder], [GroundOverlay], [NetworkLink], [Placemark], or [ScreenOverlay]—the value for
     * the [Feature]'s inline style takes precedence over the value for the shared style.
     */
    @XmlPolyChildren(
        [
            "earth.worldwind.formats.kml.models.Style",
            "earth.worldwind.formats.kml.models.StyleMap",
        ]
    )
    val styleSelector: List<StyleSelector> = emptyList(),

    /**
     * Features and geometry associated with a Region are drawn only when the Region is active. See [Region].
     */
    @XmlElement
    val region: Region? = null,

    /**
     * Allows you to add custom data to a KML file. This data can be (1) data that references an external XML schema,
     * (2) untyped data/value pairs, or (3) typed data.
     * A given KML Feature can contain a combination of these types of custom data.
     */
    @XmlElement
    val extendedData: ExtendedData? = null,
) : Object()