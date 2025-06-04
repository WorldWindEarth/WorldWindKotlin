package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
internal data class Point(
    /**
     * Any altitude value should be accompanied by an <altitudeMode> element, which tells Google Earth how to read the
     * altitude value. Altitudes can be measured:
     *
     * - from the surface of the Earth (relativeToGround),
     * - above sea level (absolute), or
     * - from the bottom of major bodies of water (relativeToSeaFloor).
     *
     * It can also be ignored (clampToGround and clampToSeaFloor)
     */
    @XmlSerialName("altitudeMode")
    @XmlElement
    var altitudeMode: String? = null,

    @XmlSerialName("coordinates")
    @XmlElement
    var coordinates: Coordinate? = null
) : Geometry()