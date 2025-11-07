package earth.worldwind.formats.kml.models

import earth.worldwind.formats.kml.serializer.FlexibleBooleanSerializer
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

/**
 * Specifies the drawing style for all polygons, including polygon extrusions (which look like the walls of buildings)
 * and line extrusions (which look like solid fences).
 */
@Serializable
internal data class PolyStyle(
    override val id: String? = null,

    @XmlElement
    override var color: String = "ffffffff",

    @XmlElement
    override val colorMode: ColorMode = ColorMode.normal,

    /**
     * Boolean value. Specifies whether to fill the polygon.
     */
    @XmlElement
    @Serializable(FlexibleBooleanSerializer::class)
    val fill: Boolean = true,

    /**
     * Boolean value. Specifies whether to outline the polygon. [Polygon] outlines use the current [LineStyle].
     */
    @XmlElement
    @Serializable(FlexibleBooleanSerializer::class)
    val outline: Boolean = true,
) : ColorStyle()