package earth.worldwind.layer.mvt

import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import kotlin.time.Duration.Companion.seconds

/**
 * Source of Mapbox Vector Tile (MVT) payloads, keyed by slippy-map (z, x, y). Implementations
 * can be swapped to point at OpenMapTiles servers, Maptiler/Mapbox CDNs (with API keys),
 * Protomaps single-file archives, or local fixtures for tests.
 *
 * Implementations MUST be safe to call from multiple coroutines in parallel — [MvtVectorLayer]
 * launches several fetches concurrently.
 *
 * If the source holds any per-instance resources (HTTP clients, file handles), implement
 * [close] so callers can release them. [MvtVectorLayer.close] forwards to it.
 */
interface MvtTileSource {
    /**
     * Fetch and decode one tile. Returning `null` is a soft-fail signalling "tile genuinely
     * empty" (some HTTP 404 codepaths) vs. throwing for transport errors that should trigger
     * the layer's backoff path.
     */
    suspend fun fetchTile(z: Int, x: Int, y: Int): MvtTile?

    /**
     * Fetch the raw protobuf bytes for one tile, optionally honoring HTTP conditional
     * headers. Lets a caching layer revalidate against a stored ETag / Last-Modified
     * without re-downloading the body on 304.
     *
     * - [MvtFetchResult.Hit]         — fresh bytes; cache them.
     * - [MvtFetchResult.NotModified] — server confirmed the cached copy is current.
     * - [MvtFetchResult.Empty]       — explicit "no tile here" (typically HTTP 404).
     * - returns `null`               — source doesn't expose raw bytes; caller should
     *                                  fall back to [fetchTile] (and skip caching).
     *
     * Sources backed by HTTP should override this. Non-HTTP sources (fixtures, decoded
     * .mbtiles) can leave the default null.
     */
    suspend fun fetchTileBytes(
        z: Int, x: Int, y: Int,
        ifNoneMatch: String? = null,
        ifModifiedSince: String? = null,
    ): MvtFetchResult? = null

    /** Release any per-instance resources. Default = no-op for stateless sources. */
    fun close() {}
}

/**
 * [MvtTileSource] that never fetches over the network. Used when reconstructing an
 * [MvtVectorLayer] from a GeoPackage / IndexedDB cache without an associated service URL —
 * every fetch returns [MvtFetchResult.Empty], so the layer serves whatever is already
 * cached and skips rendering for tiles that aren't.
 */
object CacheOnlyMvtTileSource : MvtTileSource {
    override suspend fun fetchTile(z: Int, x: Int, y: Int): MvtTile? = null
    override suspend fun fetchTileBytes(
        z: Int, x: Int, y: Int, ifNoneMatch: String?, ifModifiedSince: String?,
    ): MvtFetchResult = MvtFetchResult.Empty
}

/** Outcome of [MvtTileSource.fetchTileBytes]. */
sealed class MvtFetchResult {
    /** Server delivered fresh bytes; persist them and decode. */
    data class Hit(val bytes: ByteArray, val etag: String?, val lastModified: String?) : MvtFetchResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Hit) return false
            return bytes.contentEquals(other.bytes) && etag == other.etag && lastModified == other.lastModified
        }
        override fun hashCode(): Int {
            var r = bytes.contentHashCode(); r = 31 * r + (etag?.hashCode() ?: 0); r = 31 * r + (lastModified?.hashCode() ?: 0); return r
        }
    }
    /** Conditional request returned 304 — cached copy is still current. */
    data object NotModified : MvtFetchResult()
    /** Server reported no tile (HTTP 404, zero-byte body, or non-HTTP equivalent). */
    data object Empty : MvtFetchResult()
}

/**
 * URL-template-based [MvtTileSource]. The template uses `{z}`, `{x}`, `{y}` placeholders,
 * matching the slippy-map convention used by every major vector-tile provider:
 *
 * ```
 * // OpenMapTiles-compatible self-hosted server
 * UrlTemplateMvtTileSource("https://tiles.example.com/data/v3/{z}/{x}/{y}.pbf")
 *
 * // Maptiler (needs an API key)
 * UrlTemplateMvtTileSource("https://api.maptiler.com/tiles/v3/{z}/{x}/{y}.pbf?key=YOUR_KEY")
 * ```
 *
 * HTTP 404 is treated as an empty tile (some tile servers omit ocean-only or out-of-coverage
 * tiles entirely); every other non-2xx throws so [MvtVectorLayer]'s exponential-backoff loop
 * gets the right signal.
 *
 * gzip transparency: every engine we ship (OkHttp on JVM/Android, Darwin URLSession on iOS,
 * the browser fetch API on JS) auto-decodes `Content-Encoding: gzip`, and vector tiles are
 * almost always gzipped at rest. No Ktor ContentEncoding plugin is installed.
 */
class UrlTemplateMvtTileSource(
    val urlTemplate: String,
    /** Extra request headers — typical use case is `User-Agent` for tile servers that require one. */
    val headers: Map<String, String> = emptyMap(),
    val connectTimeoutMs: Long = 5.seconds.inWholeMilliseconds,
    val requestTimeoutMs: Long = 30.seconds.inWholeMilliseconds,
    /**
     * Optional Ktor [HttpClient] configuration hook. Applies to the source's single shared
     * client at first use. Most callers leave this empty — the platform engines (OkHttp on
     * JVM/Android, Darwin URLSession on iOS, browser fetch on JS) already auto-decode
     * `Content-Encoding: gzip` transparently, which is what virtually every MVT server
     * uses.
     *
     * If you need additional transparent decompression (e.g. `Content-Encoding: br` from a
     * brotli-enabled tile server), install Ktor's `ContentEncoding` plugin here. Brotli
     * itself needs an external brotli library — Ktor doesn't ship one — so you'd add
     * something like `org.brotli:dec` on JVM/Android and a JS brotli polyfill on web.
     *
     * ```
     * clientConfig = {
     *     install(ContentEncoding) {
     *         gzip()
     *         deflate()
     *         // brotli() requires the upstream brotli artifact
     *     }
     * }
     * ```
     */
    val clientConfig: HttpClientConfig<*>.() -> Unit = {},
) : MvtTileSource {

    /**
     * Single client shared across all fetches from this source — keeps the OkHttp
     * connection pool warm. Closed via [close] when [MvtVectorLayer] releases the layer.
     */
    private val client: HttpClient by lazy {
        DefaultHttpClient(
            connectTimeout = connectTimeoutMs,
            requestTimeout = requestTimeoutMs,
            config = clientConfig,
        )
    }

    override suspend fun fetchTile(z: Int, x: Int, y: Int): MvtTile? =
        when (val res = fetchTileBytes(z, x, y)) {
            is MvtFetchResult.Hit -> if (res.bytes.isEmpty()) null else MvtDecoder.decode(res.bytes)
            MvtFetchResult.Empty -> null
            MvtFetchResult.NotModified -> null  // no cache path → 304 shouldn't happen, treat as empty
        }

    override suspend fun fetchTileBytes(
        z: Int, x: Int, y: Int,
        ifNoneMatch: String?, ifModifiedSince: String?,
    ): MvtFetchResult {
        val url = urlTemplate
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
        // Hoist out of the lambda so the receiver's `headers` (HeadersBuilder on
        // HttpRequestBuilder) doesn't shadow this class's `headers` map.
        val extraHeaders = headers
        val response = client.get(url) {
            // Don't enable Ktor's expectSuccess here — we want to inspect the status to
            // distinguish "empty tile" (404) from "transport failed" (5xx, timeouts, etc.).
            for ((k, v) in extraHeaders) header(k, v)
            if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch)
            if (ifModifiedSince != null) header("If-Modified-Since", ifModifiedSince)
        }
        val status = response.status.value
        if (status == 404) return MvtFetchResult.Empty
        if (status == 304) return MvtFetchResult.NotModified
        if (status !in 200..299) error("MVT fetch failed: HTTP $status for $url")
        val bytes = response.readRawBytes()
        val etag = response.headers["ETag"]
        val lastModified = response.headers["Last-Modified"]
        return MvtFetchResult.Hit(bytes, etag, lastModified)
    }

    override fun close() { client.close() }
}
