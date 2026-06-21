package earth.worldwind.ogc.wfs

import earth.worldwind.geom.Sector
import earth.worldwind.layer.source.BulkFeatureSource
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.CachedGeometry
import earth.worldwind.layer.source.parseGeoJsonAsFeatureRows
import earth.worldwind.ogc.WfsLayerFactory
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.HttpClientConfig
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import earth.worldwind.geom.Position

/**
 * [BulkFeatureSource] that fetches features from an OGC WFS endpoint. Delegates to
 * [WfsLayerFactory] for capabilities negotiation, output-format selection, pagination,
 * and per-page HTTP — captures the raw response body via `onResponseBody` and re-decodes
 * each page into [CachedFeatureRow]s using commonMain-only helpers (kotlinx-serialization
 * for GeoJSON, [WfsGmlReader] for GML). The decoder is cross-platform: JVM, Android, JS,
 * and iOS all share this implementation.
 *
 * Pre-3.0 versions on JVM/Android used `mil.nga.sf` (Simple Features) as an intermediate
 * representation. That dependency is JVM-only and the round-trip through SF added no
 * value beyond converting back to [CachedGeometry] — this implementation skips it.
 */
class WfsBulkFeatureSource(
    val serviceAddress: String,
    val layerName: String,
    val serviceMetadata: String? = null,
    val sector: Sector? = null,
    val maxFeatures: Int? = null,
    val cqlFilter: String? = null,
    val pageSize: Int? = null,
    val clientConfig: HttpClientConfig<*>.() -> Unit = {},
) : BulkFeatureSource {

    // One client per source (configured with clientConfig), created on first fetch and closed in
    // close() — reused across the capabilities request and every GetFeature page, and across
    // fetchAll calls, rather than opened/closed per request.
    private val clientDelegate = lazy {
        DefaultHttpClient(WfsLayerFactory.CONNECT_TIMEOUT_MS, WfsLayerFactory.REQUEST_TIMEOUT_MS, clientConfig)
    }

    override suspend fun fetchAll(): Flow<CachedFeatureRow> {
        val rows = mutableListOf<CachedFeatureRow>()
        var attempt = 1
        while (true) {
            try {
                // Rows accumulate via onResponseBody; clear before each (re)try so a retry after a
                // mid-stream failure doesn't duplicate the pages that already arrived.
                rows.clear()
                WfsLayerFactory.createLayer(
                    serviceAddress = serviceAddress,
                    typeName = layerName,
                    serviceMetadata = serviceMetadata,
                    sector = sector,
                    maxFeatures = maxFeatures,
                    cqlFilter = cqlFilter,
                    pageSize = pageSize,
                    httpClient = clientDelegate.value,
                    // Keep the in-memory RenderableLayer build cheap — we only care about the raw bytes.
                    customLogicToApplyProperties = {},
                    onResponseBody = { body, isGml -> rows += decode(body, isGml) },
                )
                return rows.asFlow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: WfsServiceException) {
                throw e // server-reported error (unknown type / bad request) — a retry won't help
            } catch (e: Exception) {
                // Transient network failures (read/connect timeout, mid-stream connection drop) are
                // common with slow or overloaded WFS servers; retry the whole fetch a few times with
                // linear backoff before surfacing the failure.
                if (attempt >= MAX_FETCH_ATTEMPTS) throw e
                logMessage(
                    WARN, "WfsBulkFeatureSource", "fetchAll",
                    "Transient WFS fetch failure (attempt $attempt/$MAX_FETCH_ATTEMPTS), retrying: " +
                            "${e::class.simpleName}: ${e.message}",
                )
                delay(RETRY_BACKOFF_MS * attempt)
                attempt++
            }
        }
    }

    override fun close() {
        if (clientDelegate.isInitialized()) clientDelegate.value.close()
    }

    private fun decode(body: String, isGml: Boolean): List<CachedFeatureRow> = if (isGml) {
        WfsGmlReader.parseFeatureRecords(body).map { record ->
            CachedFeatureRow(record.geometry.toCached(), propertiesToJson(record.properties))
        }
    } else {
        parseGeoJsonAsFeatureRows(WfsLayerFactory.sanitizeGeoJson(body))
    }

    private fun WfsGmlReader.GmlGeometry.toCached(): CachedGeometry = when (this) {
        is WfsGmlReader.GmlGeometry.PointGeom -> position.toCachedPoint(is3D)
        is WfsGmlReader.GmlGeometry.LineGeom -> CachedGeometry.LineString(positions.map { it.toCachedPoint(is3D) })
        is WfsGmlReader.GmlGeometry.PolygonGeom -> CachedGeometry.Polygon(
            buildList {
                add(CachedGeometry.LineString(exterior.map { it.toCachedPoint(is3D) }))
                for (interior in interiors) add(CachedGeometry.LineString(interior.map { it.toCachedPoint(is3D) }))
            }
        )
    }

    // Keep the altitude only when the source coordinates were 3D — including a legitimate
    // 0.0 (sea level). A 2D geometry yields z = null so it clamps to ground rather than being
    // treated as absolute-altitude-0. (Was `altitude.takeIf { it != 0.0 }`, which wrongly
    // demoted a genuine 3D-at-sea-level vertex to 2D.)
    private fun Position.toCachedPoint(is3D: Boolean): CachedGeometry.Point =
        CachedGeometry.Point(longitude.inDegrees, latitude.inDegrees, altitude.takeIf { is3D })

    private fun propertiesToJson(props: Map<String, String>): String =
        JsonObject(props.mapValues { JsonPrimitive(it.value) }).toString()

    companion object {
        /** Cache service-identity tag for WFS-backed feature layers. */
        const val SERVICE_TYPE = "WFS"

        /** Total GetFeature attempts before a transient network failure is surfaced. */
        private const val MAX_FETCH_ATTEMPTS = 3

        /** Linear backoff base between retries (multiplied by the attempt number). */
        private const val RETRY_BACKOFF_MS = 1500L
    }
}
