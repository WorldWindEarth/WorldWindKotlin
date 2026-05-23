package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.formats.geojson.GeoJsonLayerFactory
import earth.worldwind.geom.Sector
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.ogc.wfs.WfsCapabilities
import earth.worldwind.ogc.wfs.WfsFeatureType
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.Logger.makeMessage
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.adaptivity.xmlutil.serialization.XML

/**
 * Factory that creates a [RenderableLayer] populated with features retrieved from an OGC
 * Web Feature Service (WFS 2.0.0). The factory negotiates an output format with the
 * server, preferring GeoJSON so the response can be parsed by [GeoJsonLayerFactory]. If
 * the server does not advertise a GeoJSON-compatible format, layer creation fails.
 */
object WfsLayerFactory {
    private const val SERVICE = "WFS"
    private const val VERSION = "2.0.0"
    /**
     * Per-request timeouts. WFS capability documents are commonly large (tens or
     * hundreds of feature types) and GetFeature responses can be even larger, so the
     * defaults are more generous than the platform [DefaultHttpClient] defaults.
     */
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val REQUEST_TIMEOUT_MS = 120_000L
    /**
     * Output formats accepted by [createLayer], in preference order. All produce GeoJSON
     * (or a JSON variant) when fetched. Format strings match those commonly advertised by
     * GeoServer, MapServer, deegree, etc.
     */
    private val geoJsonOutputFormats = listOf(
        "application/json",
        "application/json; subtype=geojson",
        "application/geo+json",
        "json",
        "GEOJSON",
        "geojson",
    )
    private val xml = XML { defaultPolicy { ignoreUnknownChildren() } }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    /** GeoJSON spec keys retained on Feature objects. GeoServer-style extensions such as
     *  `geometry_name` are dropped because [GeoJsonLayerFactory]'s underlying data2viz
     *  parser rejects unknown members. */
    private val featureKeys = setOf("type", "id", "bbox", "geometry", "properties")
    /** GeoJSON spec keys retained on FeatureCollection objects. GeoServer adds members
     *  such as `totalFeatures`, `numberMatched`, `numberReturned`, `timeStamp`, `links`. */
    private val featureCollectionKeys = setOf("type", "bbox", "crs", "features")
    /** Property keys consulted when the response lacks a lowercase `name` — GeoJsonLayerFactory
     *  uses `name` to label Placemarks, but most WFS datasets capitalize the column. The
     *  first match (in this order) is copied into `name` so labels appear automatically. */
    private val nameAliases = listOf("name", "NAME", "Name", "NAMEASCII", "name_en", "NAME_EN")

    /**
     * Create renderable layer based on WFS feature type metadata retrieved from server
     * capabilities or decoded from parameter. Features are fetched using GetFeature and
     * decoded with [GeoJsonLayerFactory].
     *
     * @param serviceAddress WFS service address
     * @param typeName WFS feature type name to be requested
     * @param serviceMetadata Optional WFS capabilities XML string to avoid online capabilities request
     * @param displayName Optional layer display name
     * @param sector Optional bounding box filter (BBOX); defaults to the feature type's WGS84BoundingBox
     * @param maxFeatures Optional limit on the number of features fetched (translated to the WFS 2.0 `count` parameter)
     */
    suspend fun createLayer(
        serviceAddress: String,
        typeName: String,
        serviceMetadata: String? = null,
        displayName: String? = null,
        sector: Sector? = null,
        maxFeatures: Int? = null,
    ): RenderableLayer {
        require(serviceAddress.isNotEmpty()) {
            logMessage(ERROR, "WfsLayerFactory", "createLayer", "missingServiceAddress")
        }
        require(typeName.isNotEmpty()) {
            logMessage(ERROR, "WfsLayerFactory", "createLayer", "missingLayerNames")
        }
        val capabilities = decodeWfsCapabilities(serviceMetadata ?: retrieveWfsCapabilities(serviceAddress))
        val featureType = capabilities.getFeatureType(typeName) ?: error(
            makeMessage("WfsLayerFactory", "createLayer", "Specified type name was not found")
        )
        val outputFormat = selectGeoJsonFormat(featureType) ?: error(
            makeMessage(
                "WfsLayerFactory", "createLayer",
                "Feature type does not advertise a GeoJSON-compatible output format"
            )
        )
        val getFeatureUrl = determineGetFeatureUrl(featureType, serviceAddress)
        val featuresUri = Uri.parse(getFeatureUrl).buildUpon()
            .appendQueryParameter("SERVICE", SERVICE)
            .appendQueryParameter("VERSION", VERSION)
            .appendQueryParameter("REQUEST", "GetFeature")
            .appendQueryParameter("TYPENAMES", typeName)
            .appendQueryParameter("OUTPUTFORMAT", outputFormat)
            .apply {
                if (sector != null) {
                    val bbox = "${sector.minLatitude.inDegrees},${sector.minLongitude.inDegrees}," +
                            "${sector.maxLatitude.inDegrees},${sector.maxLongitude.inDegrees},urn:ogc:def:crs:EPSG::4326"
                    appendQueryParameter("BBOX", bbox)
                    appendQueryParameter("SRSNAME", "urn:ogc:def:crs:EPSG::4326")
                }
                maxFeatures?.let { appendQueryParameter("COUNT", it.toString()) }
            }
            .build()
        val responseBody = DefaultHttpClient(CONNECT_TIMEOUT_MS, REQUEST_TIMEOUT_MS).use { httpClient ->
            httpClient.get(featuresUri.toString()) { expectSuccess = true }.bodyAsText()
        }
        val sanitized = withContext(Dispatchers.Default) { sanitizeGeoJson(responseBody) }
        return GeoJsonLayerFactory.createLayer(sanitized, displayName ?: featureType.title ?: featureType.name)
    }

    /**
     * Strip GeoServer-style non-spec members (e.g. `geometry_name`, `totalFeatures`) from
     * Feature and FeatureCollection objects in the GeoJSON tree so the strict data2viz
     * Jackson parser doesn't reject the payload. Other objects pass through unchanged.
     */
    internal fun sanitizeGeoJson(text: String): String {
        val root = json.parseToJsonElement(text)
        return json.encodeToString(JsonElement.serializer(), stripUnknownGeoJsonKeys(root))
    }

    private fun stripUnknownGeoJsonKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> {
            val type = (element["type"] as? JsonPrimitive)?.contentOrNull
            val source = when (type) {
                "Feature" -> aliasFeatureName(element.filterKeys { it in featureKeys })
                "FeatureCollection" -> element.filterKeys { it in featureCollectionKeys }
                else -> element
            }
            JsonObject(source.mapValues { stripUnknownGeoJsonKeys(it.value) })
        }
        is JsonArray -> JsonArray(element.map { stripUnknownGeoJsonKeys(it) })
        else -> element
    }

    /** If a Feature's `properties` object is missing a lowercase `name`, look for the
     *  first upper-case or English variant in [nameAliases] and copy it across so
     *  GeoJsonLayerFactory can use it as the Placemark label. */
    private fun aliasFeatureName(feature: Map<String, JsonElement>): Map<String, JsonElement> {
        val properties = feature["properties"] as? JsonObject ?: return feature
        if (properties.containsKey("name")) return feature
        val source = nameAliases.firstNotNullOfOrNull { key ->
            (properties[key] as? JsonPrimitive)?.takeIf { it.isString }
        } ?: return feature
        val updatedProperties = JsonObject(properties + ("name" to source))
        return feature + ("properties" to updatedProperties)
    }

    /**
     * Attempts to retrieve a Web Feature Service (WFS) capabilities document.
     *
     * @param serviceAddress the WFS service address
     * @return WFS 2.0.0 service capabilities
     */
    suspend fun getCapabilities(serviceAddress: String) = decodeWfsCapabilities(retrieveWfsCapabilities(serviceAddress))

    private suspend fun retrieveWfsCapabilities(serviceAddress: String) = DefaultHttpClient(CONNECT_TIMEOUT_MS, REQUEST_TIMEOUT_MS).use { httpClient ->
        val serviceUri = Uri.parse(serviceAddress).buildUpon()
            .appendQueryParameter("VERSION", VERSION)
            .appendQueryParameter("SERVICE", SERVICE)
            .appendQueryParameter("REQUEST", "GetCapabilities")
            .build()
        httpClient.get(serviceUri.toString()) { expectSuccess = true }.bodyAsText()
    }

    private suspend fun decodeWfsCapabilities(xmlText: String) = withContext(Dispatchers.Default) {
        xml.decodeFromString<WfsCapabilities>(xmlText)
    }

    /**
     * Pick the first GeoJSON-compatible output format advertised by the feature type
     * itself or by the GetFeature operation. WFS 2.0 / OWS 1.1 servers vary in how they
     * publish supported output formats: most use `<ows:Parameter name="outputFormat">`,
     * but the spec also permits `<ows:Constraint name="outputFormat">` on the operation.
     * Both are consulted so unusual server flavours still negotiate correctly.
     */
    internal fun selectGeoJsonFormat(featureType: WfsFeatureType): String? {
        val advertised = mutableListOf<String>()
        advertised += featureType.outputFormats
        featureType.capabilities.operationsMetadata?.operations
            ?.firstOrNull { it.name == "GetFeature" }
            ?.let { op ->
                op.parameters.firstOrNull { it.name == "outputFormat" }?.let { advertised += it.allowedValues }
                op.constraints.firstOrNull { it.name == "outputFormat" }?.let { advertised += it.allowedValues }
            }
        return geoJsonOutputFormats.firstOrNull { f -> advertised.any { it.equals(f, ignoreCase = true) } }
    }

    private fun determineGetFeatureUrl(featureType: WfsFeatureType, fallback: String): String {
        val operations = featureType.capabilities.operationsMetadata?.operations.orEmpty()
        val getFeature = operations.firstOrNull { it.name == "GetFeature" }
        val getMethodUrl = getFeature?.dcps?.firstOrNull()?.getMethods?.firstOrNull()?.url
        return getMethodUrl?.takeIf { it.isNotBlank() } ?: fallback
    }
}
