package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * This is an abstract element and cannot be used directly in a KML file.
 * This element is extended by the [Camera] and [LookAt] elements.
 */
@Serializable
internal abstract class AbstractView(
    /**
     * Defines the horizontal field of view of the AbstractView during a tour.
     * This element has no effect on AbstractViews outside of a tour. [horizFov] is inserted automatically by
     * the Google Earth client (versions 6.1+) during tour recording.
     * Regular AbstractViews are assigned a value of 60; views within Street View are assigned a value of 85 to match
     * the standard Street View field of view in Google Earth. Once set, the value will be applied to subsequent views,
     * until a new value is specified.
     */
    @XmlSerialName(prefix = "gx", value = "horizFov")
    val horizFov: Double = 60.0
) : AbstractKml()