package earth.worldwind.formats.kml.models

import earth.worldwind.formats.kml.serializer.FlexibleBooleanSerializer
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
internal data class LineString(
    @XmlSerialName(prefix = "gx", value = "altitudeOffset")
    @XmlElement
    var altitudeOffset: Int? = null,

    /**
     * extends the line down to the ground.
     */
    @Serializable(with = FlexibleBooleanSerializer::class)
    @XmlElement
    var extrude: Boolean? = null,

    /**
     * breaks the line up into smaller chunks,
     */
    @Serializable(with = FlexibleBooleanSerializer::class)
    @XmlElement
    var tessellate: Boolean? = null,

    @XmlSerialName("altitudeMode")
    @XmlElement
    var altitudeMode: String? = null,

    @XmlSerialName(prefix = "gx", value = "drawOrder")
    @XmlElement
    var drawOrder: Int? = null,

    @XmlSerialName("coordinates")
    @XmlElement
    var coordinates: Coordinates? = null
) : Geometry()