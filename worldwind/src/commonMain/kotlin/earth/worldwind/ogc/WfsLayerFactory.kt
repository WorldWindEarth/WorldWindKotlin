package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.formats.geojson.GeoJsonLayerFactory
import earth.worldwind.geom.Sector
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.ogc.wfs.Wfs11Capabilities
import earth.worldwind.render.Renderable
import earth.worldwind.ogc.wfs.Wfs11FeatureType
import earth.worldwind.ogc.wfs.WfsCapabilities
import earth.worldwind.ogc.wfs.WfsFeatureType
import earth.worldwind.ogc.wfs.WfsGmlReader
import earth.worldwind.ogc.wfs.WfsServiceException
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.Logger.makeMessage
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
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
 * Web Feature Service. The factory negotiates an output format with the server (preferring
 * GeoJSON via [GeoJsonLayerFactory], falling back to GML 3.1 / 3.2 via [WfsGmlReader]), and
 * tries WFS 2.0.0 first then 1.1.0. Returns an unstyled [RenderableLayer]; pass a
 * `customLogicToApplyProperties` callback to colour or label features per server attribute.
 */
object WfsLayerFactory {
    private const val SERVICE = "WFS"
    private const val VERSION_20 = "2.0.0"
    private const val VERSION_11 = "1.1.0"
    /**
     * Per-request timeouts. WFS capability documents are commonly large (tens or
     * hundreds of feature types) and GetFeature responses can be even larger, so the
     * defaults are more generous than the platform [DefaultHttpClient] defaults.
     */
    internal const val CONNECT_TIMEOUT_MS = 10_000L
    internal const val REQUEST_TIMEOUT_MS = 120_000L
    /** When a GetFeature URL grows past this length the factory switches from GET to a
     *  form-encoded POST. 2000 chars is conservative — many servers (and proxies) cap
     *  GET URLs around 2048. CQL filters and long type-name lists are common offenders. */
    private const val MAX_GET_URL_LENGTH = 2000
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
    /** GML output formats accepted as a fallback when no GeoJSON variant is advertised.
     *  Decoded by [WfsGmlReader]. Listed in preference order — GML 3.2 first. */
    private val gmlOutputFormats = listOf(
        "application/gml+xml; version=3.2",
        "text/xml; subtype=gml/3.2",
        "text/xml; subtype=gml/3.1.1",
        "GML3",
        "GML2",
    )
    private val xml = XML { defaultPolicy { ignoreUnknownChildren() } }
    private val exceptionReportRegex = Regex("<(?:\\w+:)?ExceptionReport\\b")
    private val exceptionCodeRegex = Regex("""exceptionCode\s*=\s*["']([^"']+)["']""")
    private val locatorRegex = Regex("""\blocator\s*=\s*["']([^"']+)["']""")
    // [\s\S] matches any character including newlines on every Kotlin target — JS rejects
    // the (?s) inline flag at regex-compile time and RegexOption.DOT_MATCHES_ALL is JVM-only.
    private val exceptionTextRegex = Regex("<(?:\\w+:)?ExceptionText[^>]*>([\\s\\S]*?)</(?:\\w+:)?ExceptionText>")
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
     * Create a renderable layer for one WFS feature type. Capabilities are fetched from
     * the server (or supplied via [serviceMetadata]), an output format is negotiated, and
     * the GetFeature response is decoded into [Renderable]s.
     *
     * @param serviceAddress WFS service address
     * @param typeName WFS feature type name to be requested
     * @param serviceMetadata Optional WFS capabilities XML string to avoid online capabilities request
     * @param displayName Optional layer display name
     * @param sector Optional bounding box filter (BBOX); defaults to the feature type's WGS84BoundingBox
     * @param maxFeatures Optional limit on the number of features fetched (translated to the WFS 2.0 `count` parameter)
     * @param cqlFilter Optional CQL_FILTER expression evaluated server-side (GeoServer / MapServer / QGIS Server extension)
     * @param customLogicToApplyProperties Optional callback invoked once per feature with the
     *   created renderable as `this` and the feature's properties as the argument — use it to
     *   style placemarks/paths/polygons from server-side attributes. Works uniformly for the
     *   GeoJSON and GML decode paths.
     * @param pageSize Optional WFS 2.0 page size. When set the factory issues repeated GetFeature
     *   requests with STARTINDEX, accumulating into a single layer, until the server returns a
     *   short page or [maxFeatures] is reached. Ignored for WFS 1.1.0 endpoints (the spec lacks
     *   STARTINDEX before 2.0); pass [maxFeatures] alone for those.
     * @param clientConfig Optional [HttpClientConfig] customizer applied to every Ktor client
     *   created here — use it to install basic auth, a bearer token, custom headers, or other
     *   request-shaping behaviour required by private endpoints.
     * @param onResponseBody Optional hook invoked once per fetched page with the raw response
     *   body (post-OWS-exception check). Cache implementations use this to persist features
     *   into the backing store without re-parsing.
     */
    suspend fun createLayer(
        serviceAddress: String,
        typeName: String,
        serviceMetadata: String? = null,
        displayName: String? = null,
        sector: Sector? = null,
        maxFeatures: Int? = null,
        cqlFilter: String? = null,
        sortBy: String? = null,
        customLogicToApplyProperties: Renderable.(LinkedHashMap<String, Any?>) -> Unit = {},
        pageSize: Int? = null,
        clientConfig: HttpClientConfig<*>.() -> Unit = {},
        httpClient: HttpClient? = null,
        onResponseBody: (suspend (String, Boolean) -> Unit)? = null,
    ): RenderableLayer {
        require(serviceAddress.isNotEmpty()) {
            logMessage(ERROR, "WfsLayerFactory", "createLayer", "missingServiceAddress")
        }
        require(typeName.isNotEmpty()) {
            logMessage(ERROR, "WfsLayerFactory", "createLayer", "missingLayerNames")
        }
        // One client for the whole layer build — capabilities AND every GetFeature page — rather
        // than a fresh client per request. A caller may inject a long-lived client (the cached
        // bulk source does, to reuse it across fetches); otherwise we own one and close it here.
        // Never open+close a client per request: with a shared `preconfigured` engine, ktor's
        // close() shuts down the shared executor and the next request fails with "executor rejected".
        val client = httpClient ?: DefaultHttpClient(CONNECT_TIMEOUT_MS, REQUEST_TIMEOUT_MS, clientConfig)
        try {
            val resolved = resolveFeatureType(serviceAddress, typeName, serviceMetadata, client)
            val baseParams = buildGetFeatureParams(resolved, typeName, sector, maxFeatures, cqlFilter, sortBy)
            val finalName = displayName ?: resolved.displayName
            val paginating = pageSize != null && resolved.version == VERSION_20

            if (!paginating) {
                val (body, contentType) = fetchGetFeature(resolved.getFeatureUrl, baseParams, client)
                checkForOwsException(body)
                val effectiveIsGml = decideIsGml(resolved.isGml, contentType)
                onResponseBody?.invoke(body, effectiveIsGml)
                return decodePage(body, effectiveIsGml, finalName, customLogicToApplyProperties)
            }

            // Paginated path: loop STARTINDEX until the server returns fewer than the requested
            // page size or we reach maxFeatures. First page seeds the result layer so any
            // userProperties (id, sector) set by GeoJsonLayerFactory survive into the merged layer.
            // STARTINDEX advances by the *feature* count the server returned — not the renderable
            // count, which can be inflated by multi-geometry features (a country with mainland +
            // islands expands into multiple Polygons but is still one row server-side).
            val cap = maxFeatures ?: Int.MAX_VALUE
            var result: RenderableLayer? = null
            var fetched = 0
            var startIndex = 0
            while (fetched < cap) {
                val thisPageSize = minOf(pageSize, cap - fetched)
                val pageParams = LinkedHashMap(baseParams).apply {
                    put("STARTINDEX", startIndex.toString())
                    put(resolved.countParam, thisPageSize.toString())
                }
                val (body, contentType) = fetchGetFeature(resolved.getFeatureUrl, pageParams, client)
                checkForOwsException(body)
                val effectiveIsGml = decideIsGml(resolved.isGml, contentType)
                onResponseBody?.invoke(body, effectiveIsGml)
                val featureCount = countFeaturesInResponse(body, effectiveIsGml)
                val pageLayer = decodePage(body, effectiveIsGml, finalName, customLogicToApplyProperties)
                if (result == null) result = pageLayer else result.addAllRenderables(pageLayer.toList())
                if (featureCount == 0) break
                fetched += featureCount
                if (featureCount < thisPageSize) break // server returned a short page → no more data
                startIndex += featureCount
            }
            return result ?: RenderableLayer(finalName)
        } finally {
            if (httpClient == null) client.close()
        }
    }

    /** Count server-side features in a GetFeature response. GeoJSON: length of the
     *  `features` array. GML: number of `<wfs:member>` / `<gml:featureMember>` (or
     *  unprefixed) wrappers. Used for pagination so STARTINDEX advances by the count
     *  the server actually returned rather than the (potentially inflated) renderable count. */
    internal fun countFeaturesInResponse(body: String, isGml: Boolean): Int = try {
        if (isGml) {
            Regex("<(?:\\w+:)?(?:member|featureMember)\\b").findAll(body).count()
        } else {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return 0
            (root["features"] as? JsonArray)?.size ?: 0
        }
    } catch (_: Throwable) {
        0
    }

    /** Override the negotiated [advertisedIsGml] with what the server actually returned,
     *  inferred from the response Content-Type. Some servers ignore the requested
     *  outputFormat and return whatever they please; reading the header keeps decoding
     *  honest. Returns [advertisedIsGml] unchanged when the header is missing or unclear. */
    internal fun decideIsGml(advertisedIsGml: Boolean, contentType: String?): Boolean {
        if (contentType == null) return advertisedIsGml
        val lc = contentType.lowercase()
        return when {
            "json" in lc -> false
            "xml" in lc || "gml" in lc -> true
            else -> advertisedIsGml
        }
    }

    /** Decode one GetFeature response (already vetted for OWS exceptions) into a [RenderableLayer]. */
    private suspend fun decodePage(
        responseBody: String,
        isGml: Boolean,
        finalName: String,
        customLogicToApplyProperties: Renderable.(LinkedHashMap<String, Any?>) -> Unit,
    ): RenderableLayer = if (isGml) {
        val records = withContext(Dispatchers.Default) { WfsGmlReader.parseFeatureRecords(responseBody) }
        val renderables = WfsGmlReader.toRenderables(records, customLogicToApplyProperties)
        RenderableLayer(finalName).apply { addAllRenderables(renderables) }
    } else {
        val sanitized = withContext(Dispatchers.Default) { sanitizeGeoJson(responseBody) }
        GeoJsonLayerFactory.createLayer(sanitized, finalName, customLogicToApplyProperties = customLogicToApplyProperties)
    }

    /** Build the GetFeature key/value parameter map. Order is preserved (LinkedHashMap)
     *  so the resulting URL is deterministic and replay-friendly. */
    internal fun buildGetFeatureParams(
        resolved: WfsResolved,
        typeName: String,
        sector: Sector?,
        maxFeatures: Int?,
        cqlFilter: String?,
        sortBy: String? = null,
    ): LinkedHashMap<String, String> {
        val params = LinkedHashMap<String, String>()
        params["SERVICE"] = SERVICE
        params["VERSION"] = resolved.version
        params["REQUEST"] = "GetFeature"
        params[resolved.typeNameParam] = typeName
        params["OUTPUTFORMAT"] = resolved.outputFormat
        if (sector != null) {
            params["BBOX"] = "${sector.minLatitude.inDegrees},${sector.minLongitude.inDegrees}," +
                    "${sector.maxLatitude.inDegrees},${sector.maxLongitude.inDegrees},urn:ogc:def:crs:EPSG::4326"
            params["SRSNAME"] = "urn:ogc:def:crs:EPSG::4326"
        }
        maxFeatures?.let { params[resolved.countParam] = it.toString() }
        cqlFilter?.takeIf { it.isNotBlank() }?.let { params["CQL_FILTER"] = it }
        // A stable sort key makes STARTINDEX paging non-overlapping/non-skipping (WFS has no default
        // order). Typically the feature-id property; omit for single-request (unpaged) fetches.
        sortBy?.takeIf { it.isNotBlank() }?.let { params["SORTBY"] = it }
        return params
    }

    /** Issue the GetFeature call. Tries a GET first; if the resulting URL would exceed
     *  [MAX_GET_URL_LENGTH] the request falls back to a form-encoded POST against the
     *  same endpoint, which is the spec-mandated alternative for long parameter sets.
     *  Returns the response body together with the server's Content-Type so the caller
     *  can sanity-check the negotiated output format. */
    private suspend fun fetchGetFeature(
        endpoint: String,
        params: Map<String, String>,
        httpClient: HttpClient,
    ): Pair<String, String?> {
        val uriBuilder = Uri.parse(endpoint).buildUpon()
        params.forEach { (k, v) -> uriBuilder.appendQueryParameter(k, v) }
        val getUrl = uriBuilder.build().toString()
        val response = if (getUrl.length <= MAX_GET_URL_LENGTH) {
            httpClient.get(getUrl) { expectSuccess = true }
        } else {
            httpClient.submitForm(
                url = endpoint,
                formParameters = Parameters.build { params.forEach { (k, v) -> append(k, v) } },
            ) { expectSuccess = true }
        }
        return response.bodyAsText() to response.contentType()?.toString()
    }

    /** Per-version glue: the GetFeature URL, the version string, the chosen output format
     *  (and whether it's GML rather than GeoJSON), and the version-specific query
     *  parameter names (`TYPENAMES`/`TYPENAME`, `COUNT`/`MAXFEATURES`). */
    internal data class WfsResolved(
        val version: String,
        val displayName: String,
        val getFeatureUrl: String,
        val outputFormat: String,
        val isGml: Boolean,
        val typeNameParam: String,
        val countParam: String,
    )

    /** Try WFS 2.0 first, fall back to 1.1.0 if 2.0 parsing or feature-type lookup fails. */
    /**
     * Fetch the service's GetCapabilities document once (trying WFS 2.0.0 then 1.1.0). Callers that
     * issue many requests against one service — e.g. [earth.worldwind.ogc.wfs.WfsTiledFeatureSource]
     * fetching per tile — should retrieve this ONCE and pass it as `serviceMetadata` to [createLayer],
     * so the (large) capabilities document isn't re-downloaded for every request (which can overwhelm
     * a server into timing the capabilities request out). Null if neither version could be retrieved.
     */
    suspend fun retrieveServiceMetadata(serviceAddress: String, httpClient: HttpClient): String? {
        val v20 = runCatching { retrieveCapabilities(serviceAddress, VERSION_20, httpClient) }
        v20.getOrNull()?.let { return it }
        v20.exceptionOrNull()?.let { logMessage(WARN, "WfsLayerFactory", "retrieveServiceMetadata",
            "GetCapabilities 2.0.0 failed: ${it::class.simpleName}: ${it.message}") }
        val v11 = runCatching { retrieveCapabilities(serviceAddress, VERSION_11, httpClient) }
        v11.exceptionOrNull()?.let { logMessage(WARN, "WfsLayerFactory", "retrieveServiceMetadata",
            "GetCapabilities 1.1.0 failed: ${it::class.simpleName}: ${it.message}") }
        return v11.getOrNull()
    }

    private suspend fun resolveFeatureType(
        serviceAddress: String,
        typeName: String,
        serviceMetadata: String?,
        httpClient: HttpClient,
    ): WfsResolved {
        val caps20Text = serviceMetadata ?: runCatching { retrieveCapabilities(serviceAddress, VERSION_20, httpClient) }.getOrNull()
        if (caps20Text != null) {
            val caps20 = runCatching { decodeWfs20Capabilities(caps20Text) }.getOrNull()
            val featureType20 = caps20?.getFeatureType(typeName)
            if (featureType20 != null) {
                val (format, isGml) = selectOutputFormat(featureType20) ?: error(
                    makeMessage("WfsLayerFactory", "resolveFeatureType", "Feature type does not advertise a supported output format (GeoJSON or GML)")
                )
                return WfsResolved(
                    version = VERSION_20,
                    displayName = featureType20.title ?: featureType20.name,
                    getFeatureUrl = determineGetFeatureUrl(featureType20, serviceAddress),
                    outputFormat = format,
                    isGml = isGml,
                    typeNameParam = "TYPENAMES",
                    countParam = "COUNT",
                )
            }
        }
        val caps11Text = serviceMetadata ?: runCatching { retrieveCapabilities(serviceAddress, VERSION_11, httpClient) }.getOrNull()
            ?: error(makeMessage("WfsLayerFactory", "resolveFeatureType", "Could not retrieve WFS capabilities (tried 2.0.0 and 1.1.0)"))
        val caps11 = decodeWfs11Capabilities(caps11Text)
        val featureType11 = caps11.getFeatureType(typeName) ?: error(
            makeMessage("WfsLayerFactory", "resolveFeatureType", "Specified type name was not found")
        )
        val (format, isGml) = selectOutputFormat11(featureType11) ?: error(
            makeMessage("WfsLayerFactory", "resolveFeatureType", "Feature type does not advertise a supported output format (GeoJSON or GML)")
        )
        return WfsResolved(
            version = VERSION_11,
            displayName = featureType11.title ?: featureType11.name,
            getFeatureUrl = determineGetFeatureUrl11(featureType11, serviceAddress),
            outputFormat = format,
            isGml = isGml,
            typeNameParam = "TYPENAME",
            countParam = "MAXFEATURES",
        )
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
    suspend fun getCapabilities(serviceAddress: String, clientConfig: HttpClientConfig<*>.() -> Unit = {}) =
        DefaultHttpClient(CONNECT_TIMEOUT_MS, REQUEST_TIMEOUT_MS, clientConfig).use { httpClient ->
            decodeWfs20Capabilities(retrieveCapabilities(serviceAddress, VERSION_20, httpClient))
        }

    private suspend fun retrieveCapabilities(
        serviceAddress: String,
        version: String,
        httpClient: HttpClient,
    ): String {
        val serviceUri = Uri.parse(serviceAddress).buildUpon()
            .appendQueryParameter("VERSION", version)
            .appendQueryParameter("SERVICE", SERVICE)
            .appendQueryParameter("REQUEST", "GetCapabilities")
            .build()
        return httpClient.get(serviceUri.toString()) { expectSuccess = true }.bodyAsText().also { checkForOwsException(it) }
    }

    /**
     * Detects whether a WFS response is in fact an OGC ExceptionReport and, if so, throws
     * [WfsServiceException] with the server-supplied code and text. WFS 2.0 servers use
     * OWS 1.1, WFS 1.1 uses OWS 1.0, and some servers omit the namespace prefix entirely;
     * we deliberately match against any of those forms via lenient regexes rather than
     * adding parallel @Serializable classes per OWS version.
     */
    internal fun checkForOwsException(body: String) {
        if (!exceptionReportRegex.containsMatchIn(body)) return
        val code = exceptionCodeRegex.find(body)?.groups?.get(1)?.value
        val locator = locatorRegex.find(body)?.groups?.get(1)?.value
        val text = exceptionTextRegex.find(body)?.groups?.get(1)?.value?.trim()
        throw WfsServiceException(code, text, locator)
    }

    private suspend fun decodeWfs20Capabilities(xmlText: String) = withContext(Dispatchers.Default) {
        xml.decodeFromString<WfsCapabilities>(xmlText)
    }

    private suspend fun decodeWfs11Capabilities(xmlText: String) = withContext(Dispatchers.Default) {
        xml.decodeFromString<Wfs11Capabilities>(xmlText)
    }

    /**
     * Pick a compatible output format advertised by the feature type or by the GetFeature
     * operation, preferring GeoJSON variants over GML. WFS 2.0 / OWS 1.1 servers vary in
     * how they publish supported output formats: most use `<ows:Parameter name="outputFormat">`,
     * but the spec also permits `<ows:Constraint name="outputFormat">` on the operation.
     * Both are consulted so unusual server flavours still negotiate correctly. Returns a
     * (format, isGml) pair so the caller can pick the right decoder, or null when no
     * supported format is advertised.
     */
    internal fun selectOutputFormat(featureType: WfsFeatureType): Pair<String, Boolean>? {
        val advertised = collectAdvertisedFormats(featureType)
        return pickFormat(advertised)
    }

    /** Backwards-compatible accessor returning only the GeoJSON match (or null). */
    internal fun selectGeoJsonFormat(featureType: WfsFeatureType): String? =
        selectOutputFormat(featureType)?.takeUnless { it.second }?.first

    private fun collectAdvertisedFormats(featureType: WfsFeatureType): List<String> {
        val advertised = mutableListOf<String>()
        advertised += featureType.outputFormats
        featureType.capabilities.operationsMetadata?.operations
            ?.firstOrNull { it.name == "GetFeature" }
            ?.let { op ->
                op.parameters.firstOrNull { it.name == "outputFormat" }?.let { advertised += it.allowedValues }
                op.constraints.firstOrNull { it.name == "outputFormat" }?.let { advertised += it.allowedValues }
            }
        return advertised
    }

    private fun pickFormat(advertised: List<String>): Pair<String, Boolean>? {
        geoJsonOutputFormats.firstOrNull { f -> advertised.any { it.equals(f, ignoreCase = true) } }?.let { return it to false }
        gmlOutputFormats.firstOrNull { f -> advertised.any { it.equals(f, ignoreCase = true) } }?.let { return it to true }
        return null
    }

    private fun determineGetFeatureUrl(featureType: WfsFeatureType, fallback: String): String {
        val operations = featureType.capabilities.operationsMetadata?.operations.orEmpty()
        val getFeature = operations.firstOrNull { it.name == "GetFeature" }
        val getMethodUrl = getFeature?.dcps?.firstOrNull()?.getMethods?.firstOrNull()?.url
        return getMethodUrl?.takeIf { it.isNotBlank() } ?: fallback
    }

    /** WFS 1.1.0 counterpart to [selectOutputFormat]. */
    internal fun selectOutputFormat11(featureType: Wfs11FeatureType): Pair<String, Boolean>? {
        val advertised = mutableListOf<String>()
        advertised += featureType.outputFormats
        featureType.capabilities.operationsMetadata?.operations
            ?.firstOrNull { it.name == "GetFeature" }
            ?.parameters?.firstOrNull { it.name == "outputFormat" }
            ?.allowedValues?.let { advertised += it }
        return pickFormat(advertised)
    }

    internal fun selectGeoJsonFormat11(featureType: Wfs11FeatureType): String? =
        selectOutputFormat11(featureType)?.takeUnless { it.second }?.first

    private fun determineGetFeatureUrl11(featureType: Wfs11FeatureType, fallback: String): String {
        val operations = featureType.capabilities.operationsMetadata?.operations.orEmpty()
        val getFeature = operations.firstOrNull { it.name == "GetFeature" }
        val getMethodUrl = getFeature?.dcps?.firstOrNull()?.http?.getMethods?.firstOrNull()?.url
        return getMethodUrl?.takeIf { it.isNotBlank() } ?: fallback
    }
}
