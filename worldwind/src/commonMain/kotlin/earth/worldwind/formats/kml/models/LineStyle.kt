package earth.worldwind.formats.kml.models

import earth.worldwind.formats.kml.serializer.FlexibleBooleanSerializer
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Specifies the drawing style (color, color mode, and line width) for all line geometry.
 * Line geometry includes the outlines of outlined polygons and the extruded "tether" of Placemark icons
 * (if extrusion is enabled).
 */
@Serializable
internal data class LineStyle(
    override val id: String? = null,

    @XmlElement
    override var color: String = "ffffffff",

    @XmlElement
    override val colorMode: ColorMode = ColorMode.normal,

    /**
     * Width of the line, in pixels.
     */
    @XmlElement
    val width: Float = 1.0f,

    /**
     * Color of the portion of the line defined by [outerWidth]. Note that the [outerColor] and [outerWidth] elements
     * are ignored when [LineStyle] is applied to [Polygon] and [LinearRing].
     */
    @XmlSerialName(prefix = "gx", value = "outerColor")
    @XmlElement
    val outerColor: String = "ffffffff",

    /**
     * A value between 0.0 and 1.0 that specifies the proportion of the line that uses the [outerColor].
     * Only applies to lines setting width with [physicalWidth]; it does not apply to lines using [width].
     * See also [LineString.drawOrder]. A draw order value may be necessary if dual-colored lines are crossing each
     * other—for example, for showing freeway interchanges.
     */
    @XmlSerialName(prefix = "gx", value = "outerWidth")
    @XmlElement
    val outerWidth: Float = 0.0f,

    /**
     * Physical width of the line, in meters.
     */
    @XmlSerialName(prefix = "gx", value = "physicalWidth")
    @XmlElement
    val physicalWidth: Double = 0.0,

    /**
     * A boolean defining whether or not to display a text label on a LineString. A LineString's label is contained
     * in the [name] element that is a sibling of [LineString] (i.e. contained within the same [Placemark] element).
     * Google Earth version 6.1+ does not display labels by default;
     * they must be enabled for each LineString by setting [labelVisibility] to 1.
     */
    @XmlSerialName(prefix = "gx", value = "labelVisibility")
    @XmlElement
    @Serializable(FlexibleBooleanSerializer::class)
    val labelVisibility: Boolean = false,
) : ColorStyle()