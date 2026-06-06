package earth.worldwind.formats.shapefile

import earth.worldwind.layer.source.BulkFeatureSource
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.CachedGeometry
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Path
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.TriangleMesh
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement

/**
 * [BulkFeatureSource] for ESRI Shapefiles. Downloads the .shp/.dbf/.prj/.cpg quartet from
 * [shpUrl] (sidecars derived by extension), parses every record through
 * [ShapefileLayerFactory], and emits one [CachedFeatureRow] per record with the geometry
 * extracted as a [CachedGeometry] and the DBF attributes encoded as a JSON object string.
 *
 * MultiPatch records produce [TriangleMesh] renderables on the factory path — those are
 * dropped here because [CachedGeometry] has no triangle-index model. Use a non-cached
 * source if you need MultiPatch.
 */
class ShapefileBulkFeatureSource(
    val shpUrl: String,
) : BulkFeatureSource {

    // One client per source, created on first fetch and closed in close() — NOT a fresh client
    // per fetchAll. Opening + closing a DefaultHttpClient per fetch churns OkHttp dispatchers /
    // connection pools, and when a shared `httpClientCustomizer { engine { preconfigured = ... } }`
    // client is installed, ktor's close() shuts down that shared executor and breaks every other
    // source's request ("executor rejected").
    private val clientDelegate = lazy { DefaultHttpClient(CONNECT_TIMEOUT_MS, REQUEST_TIMEOUT_MS) }
    private val client get() = clientDelegate.value

    override suspend fun fetchAll(): Flow<CachedFeatureRow> {
        val baseUrl = stripShpExtension(shpUrl)
        val shpBytes = client.get(shpUrl) { expectSuccess = true }.readRawBytes()
        val dbfBytes = fetchOptional("$baseUrl.dbf") ?: fetchOptional("$baseUrl.DBF")
        val prjBytes = fetchOptional("$baseUrl.prj") ?: fetchOptional("$baseUrl.PRJ")
        val cpgBytes = fetchOptional("$baseUrl.cpg") ?: fetchOptional("$baseUrl.CPG")
        val sidecars = Sidecars(shpBytes, dbfBytes, prjBytes, cpgBytes)
        val prj = sidecars.prj?.let { runCatching { PrjFile(decodeUtf8OrLatin1(it)) }.getOrNull() }
        val cpgText = sidecars.cpg?.let { decodeUtf8OrLatin1(it) }
        val dbf = sidecars.dbf?.let { runCatching { DBaseFile(it, cpgText = cpgText) }.getOrNull() }
        val shapefile = Shapefile(sidecars.shp, projection = prj, attributes = dbf)

        // Faithful 2D-vs-3D at the cache boundary: emit z=null for plain 2D shapefiles
        // (POINT / POLYLINE / POLYGON without Z) so the consumer can clamp them to terrain;
        // 3D shapefiles (POINT_Z / POLYLINE_Z / POLYGON_Z / MULTI_PATCH) keep their z. We
        // consult the shapefile header rather than [Position.altitude] because the latter
        // is always a non-null Double and defaults to 0.0 — indistinguishable from a
        // legitimate sea-level z if we didn't know the file's dimensionality.
        val hasZ = shapefile.shapeType.isZ
        val rows = mutableListOf<CachedFeatureRow>()
        ShapefileLayerFactory.emitRecordRenderables(shapefile) { record, renderable ->
            encodeRow(renderable, record.attributes, hasZ)?.let { rows += it }
        }
        return rows.asFlow()
    }

    private fun encodeRow(renderable: Renderable, attrs: Map<String, Any?>, hasZ: Boolean): CachedFeatureRow? {
        val geometry = when (renderable) {
            is Placemark -> {
                val p = renderable.position
                CachedGeometry.Point(p.longitude.inDegrees, p.latitude.inDegrees, p.altitude.takeIf { hasZ })
            }
            is Path -> CachedGeometry.LineString(renderable.positions.map {
                CachedGeometry.Point(it.longitude.inDegrees, it.latitude.inDegrees, it.altitude.takeIf { hasZ })
            })
            is Polygon -> {
                val rings = (0 until renderable.boundaryCount).map { i ->
                    CachedGeometry.LineString(renderable.getBoundary(i).map {
                        CachedGeometry.Point(it.longitude.inDegrees, it.latitude.inDegrees, it.altitude.takeIf { hasZ })
                    })
                }
                CachedGeometry.Polygon(rings)
            }
            is TriangleMesh -> {
                log(WARN, "ShapefileBulkFeatureSource: TriangleMesh from MultiPatch not cacheable")
                return null
            }
            else -> return null
        }
        return CachedFeatureRow(geometry, attributesToJsonString(attrs))
    }

    private fun attributesToJsonString(attrs: Map<String, Any?>): String? {
        if (attrs.isEmpty()) return null
        val map = LinkedHashMap<String, JsonElement>(attrs.size)
        for ((k, v) in attrs) if (v != null) map[k] = JsonPrimitive(v.toString())
        return JsonObject(map).toString()
    }

    override fun close() {
        if (clientDelegate.isInitialized()) client.close()
    }

    private suspend fun fetchOptional(url: String): ByteArray? = try {
        client.get(url) { expectSuccess = true }.readRawBytes()
    } catch (_: Throwable) {
        null
    }

    private fun stripShpExtension(url: String): String {
        val lower = url.lowercase()
        val idx = lower.lastIndexOf(".shp")
        return if (idx >= 0) url.substring(0, idx) else url
    }

    private fun decodeUtf8OrLatin1(bytes: ByteArray): String = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: Throwable) {
        buildString(bytes.size) { for (b in bytes) append(Char(b.toInt() and 0xFF)) }
    }

    private data class Sidecars(val shp: ByteArray, val dbf: ByteArray?, val prj: ByteArray?, val cpg: ByteArray?)

    companion object {
        /** Web-service type tag — written into `gpkg_web_service` when wiring a cache so
         *  `ContentManager.openLayer<BulkFeatureLayer>` can reconstruct the source on subsequent loads. */
        const val SERVICE_TYPE = "Shapefile"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val REQUEST_TIMEOUT_MS = 120_000L
    }
}
