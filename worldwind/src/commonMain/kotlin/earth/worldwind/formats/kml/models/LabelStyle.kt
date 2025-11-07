package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

/**
 * Specifies how the [name] of a Feature is drawn in the 3D viewer.
 * A custom color, color mode, and scale for the label (name) can be specified.
 */
@Serializable
internal data class LabelStyle(
    override val id: String? = null,

    @XmlElement
    override var color: String = "ffffffff",

    @XmlElement
    override val colorMode: ColorMode = ColorMode.normal,

    /**
     * Resizes the label.
     */
    @XmlElement
    val scale: Float = 1.0f,
) : ColorStyle()