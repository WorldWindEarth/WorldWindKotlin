package earth.worldwind.formats.kml.models

import earth.worldwind.formats.kml.serializer.FlexibleBooleanSerializer
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
internal data class LinearRing(
    @XmlSerialName(prefix = "gx", value = "altitudeOffset")
    @XmlElement
    var altitudeOffset: Double? = null,

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

    @XmlSerialName("coordinates")
    @XmlElement
    var coordinates: Coordinates? = null
) : Geometry()