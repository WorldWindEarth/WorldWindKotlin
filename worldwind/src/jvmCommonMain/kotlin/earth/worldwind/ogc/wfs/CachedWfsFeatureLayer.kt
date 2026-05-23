package earth.worldwind.ogc.wfs

import earth.worldwind.geom.Sector
import earth.worldwind.layer.CacheableFeatureLayer
import earth.worldwind.layer.FeatureCacheSourceFactory
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.geoJsonGeometryToSf
import earth.worldwind.ogc.gpkg.gmlFeatureRecordsToSf
import earth.worldwind.render.Renderable
import io.ktor.client.HttpClientConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import mil.nga.sf.Geometry

/**
 * JVM/Android [WfsFeatureLayer] variant that also implements [CacheableFeatureLayer] —
 * each [refresh] writes the fetched features into a GPKG features table via its bound
 * [GpkgFeatureCacheFactory], and the layer can also be populated directly from the cache
 * without hitting the network. Parallels the way [WmsImageLayer] +
 * [earth.worldwind.layer.CacheableImageLayer] cooperate for tile services.
 */
class CachedWfsFeatureLayer(
    serviceAddress: String,
    layerName: String,
    serviceMetadata: String? = null,
    outputFormat: String = "application/json",
    displayName: String? = null,
    cqlFilter: String? = null,
    customLogicToApplyProperties: Renderable.(LinkedHashMap<String, Any?>) -> Unit = {},
    pageSize: Int? = null,
    clientConfig: HttpClientConfig<*>.() -> Unit = {},
) : WfsFeatureLayer(
    serviceAddress, layerName, serviceMetadata, outputFormat, displayName,
    cqlFilter, customLogicToApplyProperties, pageSize, clientConfig,
), CacheableFeatureLayer {

    override var cacheSourceFactory: FeatureCacheSourceFactory? = null

    /**
     * Refresh from the server, replacing renderables AND (when a cache is bound) writing
     * every fetched feature into the GPKG features table in one transaction at the end.
     * `onResponseBody` fires per page so the accumulator captures all pages a single
     * paginated refresh produced before the cache is flushed.
     */
    override suspend fun refresh(
        sector: Sector?, maxFeatures: Int?, onResponseBody: (suspend (String, Boolean) -> Unit)?,
    ) {
        val cache = cacheSourceFactory as? earth.worldwind.ogc.GpkgFeatureCacheFactory
        if (cache == null) {
            super.refresh(sector, maxFeatures, onResponseBody)
            return
        }
        val accumulator = mutableListOf<Pair<Geometry, String?>>()
        super.refresh(sector, maxFeatures) { body, isGml ->
            // Both decode paths feed the cache. GeoJSON: walk the FeatureCollection's
            // features array (cheap — we already have the JSON tree). GML: re-run the
            // GML pull-parser to recover (geometry, properties) pairs and convert each
            // geometry to mil.nga.sf form for the GPKG blob encoder.
            if (isGml) {
                accumulator += gmlFeatureRecordsToSf(WfsGmlReader.parseFeatureRecords(body))
            } else {
                extractGeoJsonFeatures(body, accumulator)
            }
            onResponseBody?.invoke(body, isGml)
        }
        cache.geoPackage.replaceCachedFeatures(cache.content, accumulator)
    }

    /**
     * Populate the layer's renderables from the cache without touching the network. Each
     * cached row's `properties` JSON is decoded back into a [LinkedHashMap] and handed
     * to the configured `customLogicToApplyProperties` callback so styling survives.
     */
    suspend fun loadFromCache() {
        val cache = (cacheSourceFactory as? earth.worldwind.ogc.GpkgFeatureCacheFactory) ?: return
        val rows = cache.geoPackage.readCachedFeatures(cache.content)
        val srsId = cache.content.srs?.srsId ?: GeoPackage.EPSG_4326
        val rendered = rows.flatMap { (geometry, propertiesJson) ->
            val properties = propertiesJson?.let { parsePropertiesMap(it) } ?: LinkedHashMap()
            cache.geoPackage.geometryToRenderables(geometry, null, srsId).onEach {
                it.customLogicToApplyProperties(properties)
            }
        }
        clearRenderables()
        addAllRenderables(rendered)
    }

    private fun extractGeoJsonFeatures(body: String, into: MutableList<Pair<Geometry, String?>>) {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return
        val features = root["features"] as? JsonArray ?: return
        for (element in features) {
            val feature = element as? JsonObject ?: continue
            val geometryObj = feature["geometry"] as? JsonObject ?: continue
            val sfGeom = geoJsonGeometryToSf(geometryObj) ?: continue
            val properties = (feature["properties"] as? JsonObject)?.toString()
            into += sfGeom to properties
        }
    }

    private fun parsePropertiesMap(text: String): LinkedHashMap<String, Any?> {
        val obj = json.parseToJsonElement(text) as? JsonObject ?: return LinkedHashMap()
        val result = LinkedHashMap<String, Any?>(obj.size)
        obj.forEach { (k, v) ->
            result[k] = jsonValueToAny(v)
        }
        return result
    }

    private fun jsonValueToAny(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonPrimitive -> when {
            element.isString -> element.content
            else -> element.content.toDoubleOrNull() ?: element.content.toLongOrNull() ?: element.content
        }
        is JsonObject -> parsePropertiesMap(element.toString())
        is JsonArray -> element.map { jsonValueToAny(it) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

