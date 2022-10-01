package earth.worldwind.ogc.wms

import earth.worldwind.geom.Sector.Companion.fromDegrees
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("EX_GeographicBoundingBox", WMS_NAMESPACE, WMS_PREFIX)
data class WmsGeographicBoundingBox(
    @XmlElement(true)
    @XmlSerialName("northBoundLatitude", WMS_NAMESPACE, WMS_PREFIX)
    private val north: Double,
    @XmlElement(true)
    @XmlSerialName("eastBoundLongitude", WMS_NAMESPACE, WMS_PREFIX)
    private val east: Double,
    @XmlElement(true)
    @XmlSerialName("southBoundLatitude", WMS_NAMESPACE, WMS_PREFIX)
    private val south: Double,
    @XmlElement(true)
    @XmlSerialName("westBoundLongitude", WMS_NAMESPACE, WMS_PREFIX)
    private val west: Double
) {
    val geographicBoundingBox get() = fromDegrees(south, west, north - south, east - west)
}