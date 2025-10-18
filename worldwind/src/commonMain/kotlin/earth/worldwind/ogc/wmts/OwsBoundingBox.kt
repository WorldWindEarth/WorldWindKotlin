package earth.worldwind.ogc.wmts

import earth.worldwind.geom.Sector
import earth.worldwind.util.Logger
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("BoundingBox", OWS11_NAMESPACE, OWS11_PREFIX)
data class OwsBoundingBox(
    val crs: String? = null,
    @XmlElement
    @XmlSerialName("LowerCorner", OWS11_NAMESPACE, OWS11_PREFIX)
    val lowerCorner: String,
    @XmlElement
    @XmlSerialName("UpperCorner", OWS11_NAMESPACE, OWS11_PREFIX)
    val upperCorner: String
) {
    val sector get() = try {
        val regex = "\\s+".toRegex()
        val lowerValues = lowerCorner.split(regex)
        val upperValues = upperCorner.split(regex)
        val minLon = lowerValues[0].toDouble()
        val minLat = lowerValues[1].toDouble()
        val maxLon = upperValues[0].toDouble()
        val maxLat = upperValues[1].toDouble()
        Sector.fromDegrees(minLat, minLon, maxLat - minLat, maxLon - minLon)
    } catch (ex: Exception) {
        Logger.logMessage(
            Logger.ERROR, "OwsBoundingBox", "sector",
            "Error parsing bounding box corners, LowerCorner=$lowerCorner UpperCorner=$upperCorner", ex
        )
        null
    }
}