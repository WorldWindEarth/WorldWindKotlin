package earth.worldwind.formats.shapefile

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.layer.CacheableFeatureLayer
import earth.worldwind.layer.FeatureCacheSourceFactory
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.ogc.GpkgFeatureCacheFactory
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Path
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.shape.TriangleMesh
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mil.nga.sf.Geometry
import mil.nga.sf.LineString
import mil.nga.sf.Point

/**
 * JVM/Android [RenderableLayer] that loads an ESRI shapefile and persists its parsed records in
 * a GeoPackage via the standard [CacheableFeatureLayer] flow. Bulk-refresh model (same as WFS):
 * first [load] fetches `.shp/.dbf/.prj` over HTTP and writes one row per renderable; subsequent
 * runs rebuild straight from cache. MultiPatch records bypass the cache — sf.Geometry has no
 * triangle-index model.
 */
class CachedShapefileLayer(
    val shpUrl: String,
    displayName: String? = null,
    val attributes: ShapeAttributes = ShapeAttributes(),
    val highlightAttributes: ShapeAttributes? = null,
    val placemarkAttributes: PlacemarkAttributes = PlacemarkAttributes(),
    val highlightPlacemarkAttributes: PlacemarkAttributes? = null,
    private val shapeConfiguration: (ShapefileRecord) -> ShapefileShapeConfiguration? = { ShapefileShapeConfiguration() },
    private val customLogicToApplyProperties: Renderable.(Map<String, String>) -> Unit = {},
) : RenderableLayer(displayName), CacheableFeatureLayer {

    override var cacheSourceFactory: FeatureCacheSourceFactory? = null

    /** Build renderables — cache hit replays; miss fetches and writes back. Idempotent. */
    suspend fun load() {
        val cache = cacheSourceFactory as? GpkgFeatureCacheFactory
        if (cache != null) {
            val rows = cache.geoPackage.readCachedFeatures(cache.content)
            if (rows.isNotEmpty()) {
                clearRenderables()
                replayFromCache(rows)
                return
            }
        }
        clearRenderables()
        val rows = fetchAndPopulate()
        if (cache != null) {
            cache.geoPackage.replaceCachedFeatures(cache.content, rows)
        }
    }

    /** Force a re-fetch, replacing renderables and cached rows. */
    suspend fun refresh() {
        clearRenderables()
        val rows = fetchAndPopulate()
        (cacheSourceFactory as? GpkgFeatureCacheFactory)?.let { cache ->
            cache.geoPackage.replaceCachedFeatures(cache.content, rows)
        }
    }

    private suspend fun fetchAndPopulate(): List<Pair<Geometry, String?>> {
        val baseUrl = stripShpExtension(shpUrl)
        val sidecars = DefaultHttpClient(CONNECT_TIMEOUT_MS, REQUEST_TIMEOUT_MS).use { httpClient ->
            val shpBytes = httpClient.get(shpUrl) { expectSuccess = true }.readRawBytes()
            val dbfBytes = fetchOptional(httpClient, "$baseUrl.dbf") ?: fetchOptional(httpClient, "$baseUrl.DBF")
            val prjBytes = fetchOptional(httpClient, "$baseUrl.prj") ?: fetchOptional(httpClient, "$baseUrl.PRJ")
            val cpgBytes = fetchOptional(httpClient, "$baseUrl.cpg") ?: fetchOptional(httpClient, "$baseUrl.CPG")
            Sidecars(shpBytes, dbfBytes, prjBytes, cpgBytes)
        }
        val prj = sidecars.prj?.let { runCatching { PrjFile(decodeUtf8OrLatin1(it)) }.getOrNull() }
        val cpgText = sidecars.cpg?.let { decodeUtf8OrLatin1(it) }
        val dbf = sidecars.dbf?.let { runCatching { DBaseFile(it, cpgText = cpgText) }.getOrNull() }
        val shapefile = Shapefile(sidecars.shp, projection = prj, attributes = dbf)

        val rows = mutableListOf<Pair<Geometry, String?>>()
        ShapefileLayerFactory.emitRecordRenderables(shapefile, shapeConfiguration) { record, renderable ->
            val attrs = stringifyAttributes(record.attributes)
            renderable.customLogicToApplyProperties(attrs)
            addRenderable(renderable)
            encodeRow(renderable, attrs)?.let { rows += it }
        }
        return rows
    }

    private fun replayFromCache(rows: List<Pair<Geometry, String?>>) {
        for ((geometry, propertiesJson) in rows) {
            val props = propertiesJson?.let { runCatching { JSON.decodeFromString<RowMeta>(it) }.getOrNull() } ?: continue
            val renderable = decodeRow(geometry, props) ?: continue
            renderable.customLogicToApplyProperties(props.attrs)
            addRenderable(renderable)
        }
    }

    private fun encodeRow(renderable: Renderable, attrs: Map<String, String>): Pair<Geometry, String?>? = when (renderable) {
        is Placemark -> {
            val p = renderable.position
            val sfPoint = Point(p.longitude.inDegrees, p.latitude.inDegrees, p.altitude)
            val meta = RowMeta(
                kind = Kind.PLACEMARK,
                altitudeMode = renderable.altitudeMode.name,
                displayName = renderable.displayName,
                attrs = attrs,
                label = renderable.label,
            )
            sfPoint to JSON.encodeToString(meta)
        }
        is Path -> {
            val ls = renderable.positions.toLineString()
            val meta = RowMeta(
                kind = Kind.PATH,
                altitudeMode = renderable.altitudeMode.name,
                isFollowTerrain = renderable.isFollowTerrain,
                displayName = renderable.displayName,
                attrs = attrs,
            )
            ls to JSON.encodeToString(meta)
        }
        is Polygon -> {
            val poly = mil.nga.sf.Polygon(true, false).apply {
                for (i in 0 until renderable.boundaryCount) addRing(renderable.getBoundary(i).toLineString())
            }
            val meta = RowMeta(
                kind = Kind.POLYGON,
                altitudeMode = renderable.altitudeMode.name,
                isExtrude = renderable.isExtrude,
                isFollowTerrain = renderable.isFollowTerrain,
                displayName = renderable.displayName,
                attrs = attrs,
            )
            poly to JSON.encodeToString(meta)
        }
        is TriangleMesh -> {
            // sf.Geometry has no triangle-index model — first-load shows the mesh, cache replay omits it.
            log(WARN, "CachedShapefileLayer: TriangleMesh from MultiPatch not cached for $shpUrl")
            null
        }
        else -> null
    }

    private fun decodeRow(geometry: Geometry, meta: RowMeta): Renderable? {
        val mode = runCatching { AltitudeMode.valueOf(meta.altitudeMode) }.getOrNull() ?: AltitudeMode.RELATIVE_TO_GROUND
        return when (meta.kind) {
            Kind.PLACEMARK -> {
                val p = geometry as? Point ?: return null
                Placemark(Position.fromDegrees(p.y, p.x, p.z ?: 0.0), placemarkAttributes, meta.label).apply {
                    altitudeMode = mode
                    highlightPlacemarkAttributes?.let { this.highlightAttributes = it }
                    meta.displayName?.let { displayName = it }
                }
            }
            Kind.PATH -> {
                val ls = geometry as? LineString ?: return null
                Path(ls.toPositions(), attributes).apply {
                    altitudeMode = mode
                    isFollowTerrain = meta.isFollowTerrain
                    highlightAttributes?.let { this.highlightAttributes = it }
                    meta.displayName?.let { displayName = it }
                }
            }
            Kind.POLYGON -> {
                val poly = geometry as? mil.nga.sf.Polygon ?: return null
                val rings = poly.rings ?: return null
                if (rings.isEmpty()) return null
                Polygon(rings.first().toPositions(), attributes).apply {
                    for (i in 1 until rings.size) addBoundary(rings[i].toPositions())
                    altitudeMode = mode
                    isExtrude = meta.isExtrude
                    isFollowTerrain = meta.isFollowTerrain
                    highlightAttributes?.let { this.highlightAttributes = it }
                    meta.displayName?.let { displayName = it }
                }
            }
        }
    }

    private fun List<Position>.toLineString(): LineString {
        val ls = LineString(true, false)
        for (p in this) ls.addPoint(Point(p.longitude.inDegrees, p.latitude.inDegrees, p.altitude))
        return ls
    }

    private fun LineString.toPositions(): List<Position> {
        val pts = points ?: return emptyList()
        return pts.map { Position.fromDegrees(it.y, it.x, it.z ?: 0.0) }
    }

    /** DBF values come as Any?; coerce to String for the JSON payload — consumers re-parse. */
    private fun stringifyAttributes(attrs: Map<String, Any?>): Map<String, String> {
        if (attrs.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>(attrs.size)
        for ((k, v) in attrs) if (v != null) result[k] = v.toString()
        return result
    }

    private suspend fun fetchOptional(httpClient: HttpClient, url: String): ByteArray? = try {
        httpClient.get(url) { expectSuccess = true }.readRawBytes()
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

    @Serializable
    private data class RowMeta(
        val kind: Kind,
        val altitudeMode: String = AltitudeMode.RELATIVE_TO_GROUND.name,
        val isExtrude: Boolean = false,
        val isFollowTerrain: Boolean = false,
        val displayName: String? = null,
        val label: String? = null,
        val attrs: Map<String, String> = emptyMap(),
    )

    @Serializable
    private enum class Kind { PLACEMARK, PATH, POLYGON }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val REQUEST_TIMEOUT_MS = 120_000L
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}
