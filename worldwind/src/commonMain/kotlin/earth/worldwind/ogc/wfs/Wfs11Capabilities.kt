package earth.worldwind.ogc.wfs

import earth.worldwind.geom.Sector
import earth.worldwind.util.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

/**
 * Minimal model for an OGC WFS 1.1.0 GetCapabilities response. Only the fields needed to
 * negotiate an output format and build a GetFeature request are mapped — service contact,
 * provider, and filter-encoding sections are skipped on purpose. WFS 1.1.0 uses OWS 1.0,
 * `DefaultSRS`/`OtherSRS` (not `DefaultCRS`/`OtherCRS`), and the GetFeature URL takes
 * `typeName` (singular) + `maxFeatures`, not `typeNames` + `count`.
 */
@Serializable
@XmlSerialName("WFS_Capabilities", WFS11_NAMESPACE, WFS11_PREFIX)
data class Wfs11Capabilities(
    val version: String = "1.1.0",
    val operationsMetadata: Wfs11OperationsMetadata? = null,
    val featureTypeList: Wfs11FeatureTypeList = Wfs11FeatureTypeList(),
) {
    val featureTypes get() = featureTypeList.featureTypes

    init {
        featureTypes.forEach { it.capabilities = this }
    }

    fun getFeatureType(name: String) = featureTypes.firstOrNull { it.name == name }
}

@Serializable
@XmlSerialName("FeatureTypeList", WFS11_NAMESPACE, WFS11_PREFIX)
data class Wfs11FeatureTypeList(
    val featureTypes: List<Wfs11FeatureType> = emptyList(),
) : Iterable<Wfs11FeatureType> by featureTypes

@Serializable
@XmlSerialName("FeatureType", WFS11_NAMESPACE, WFS11_PREFIX)
data class Wfs11FeatureType(
    @XmlElement
    @XmlSerialName("Name", WFS11_NAMESPACE, WFS11_PREFIX)
    val name: String,
    @XmlElement
    @XmlSerialName("Title", WFS11_NAMESPACE, WFS11_PREFIX)
    val title: String? = null,
    @XmlElement
    @XmlSerialName("Abstract", WFS11_NAMESPACE, WFS11_PREFIX)
    val abstract: String? = null,
    @XmlElement
    @XmlSerialName("DefaultSRS", WFS11_NAMESPACE, WFS11_PREFIX)
    val defaultSrs: String? = null,
    @XmlSerialName("OtherSRS", WFS11_NAMESPACE, WFS11_PREFIX)
    val otherSrs: List<String> = emptyList(),
    @XmlSerialName("WGS84BoundingBox", OWS10_NAMESPACE, OWS10_PREFIX)
    val wgs84BoundingBox: Ows10BoundingBox? = null,
    @XmlSerialName("OutputFormats", WFS11_NAMESPACE, WFS11_PREFIX)
    val outputFormatsWrapper: Wfs11OutputFormats? = null,
) {
    val outputFormats: List<String> get() = outputFormatsWrapper?.formats.orEmpty()

    @Transient
    lateinit var capabilities: Wfs11Capabilities
}

@Serializable
@XmlSerialName("OutputFormats", WFS11_NAMESPACE, WFS11_PREFIX)
data class Wfs11OutputFormats(
    @XmlSerialName("Format", WFS11_NAMESPACE, WFS11_PREFIX)
    val formats: List<String> = emptyList(),
)

@Serializable
@XmlSerialName("BoundingBox", OWS10_NAMESPACE, OWS10_PREFIX)
data class Ows10BoundingBox(
    val crs: String? = null,
    @XmlElement
    @XmlSerialName("LowerCorner", OWS10_NAMESPACE, OWS10_PREFIX)
    val lowerCorner: String,
    @XmlElement
    @XmlSerialName("UpperCorner", OWS10_NAMESPACE, OWS10_PREFIX)
    val upperCorner: String,
) {
    val sector get() = try {
        val regex = "\\s+".toRegex()
        val lower = lowerCorner.split(regex)
        val upper = upperCorner.split(regex)
        val minLon = lower[0].toDouble()
        val minLat = lower[1].toDouble()
        val maxLon = upper[0].toDouble()
        val maxLat = upper[1].toDouble()
        Sector.fromDegrees(minLat, minLon, maxLat - minLat, maxLon - minLon)
    } catch (ex: Exception) {
        Logger.logMessage(
            Logger.ERROR, "Ows10BoundingBox", "sector",
            "Error parsing bounding box corners, LowerCorner=$lowerCorner UpperCorner=$upperCorner", ex
        )
        null
    }
}

@Serializable
@XmlSerialName("OperationsMetadata", OWS10_NAMESPACE, OWS10_PREFIX)
data class Wfs11OperationsMetadata(
    val operations: List<Wfs11Operation> = emptyList(),
)

@Serializable
@XmlSerialName("Operation", OWS10_NAMESPACE, OWS10_PREFIX)
data class Wfs11Operation(
    val name: String,
    val dcps: List<Wfs11Dcp> = emptyList(),
    val parameters: List<Wfs11Parameter> = emptyList(),
)

@Serializable
@XmlSerialName("DCP", OWS10_NAMESPACE, OWS10_PREFIX)
data class Wfs11Dcp(
    val http: Wfs11Http,
)

@Serializable
@XmlSerialName("HTTP", OWS10_NAMESPACE, OWS10_PREFIX)
data class Wfs11Http(
    @XmlSerialName("Get", OWS10_NAMESPACE, OWS10_PREFIX)
    val getMethods: List<Wfs11HttpMethod> = emptyList(),
    @XmlSerialName("Post", OWS10_NAMESPACE, OWS10_PREFIX)
    val postMethods: List<Wfs11HttpMethod> = emptyList(),
)

@Serializable
data class Wfs11HttpMethod(
    @XmlSerialName("href", "http://www.w3.org/1999/xlink", "xlink")
    val url: String,
)

/**
 * OWS 1.0 Parameter. Older servers list `<ows:Value>` directly; newer use the
 * `<ows:AllowedValues><ows:Value>` form. Both flatten into [allowedValues].
 */
@Serializable
@XmlSerialName("Parameter", OWS10_NAMESPACE, OWS10_PREFIX)
data class Wfs11Parameter(
    val name: String,
    @XmlSerialName("Value", OWS10_NAMESPACE, OWS10_PREFIX)
    val directValues: List<String> = emptyList(),
    val allowed: Wfs11AllowedValues? = null,
) {
    val allowedValues: List<String> get() = directValues + allowed?.values.orEmpty()
}

@Serializable
@XmlSerialName("AllowedValues", OWS10_NAMESPACE, OWS10_PREFIX)
data class Wfs11AllowedValues(
    @XmlSerialName("Value", OWS10_NAMESPACE, OWS10_PREFIX)
    val values: List<String> = emptyList(),
)
