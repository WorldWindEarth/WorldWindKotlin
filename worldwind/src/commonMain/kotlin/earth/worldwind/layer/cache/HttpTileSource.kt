package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileBlob
import earth.worldwind.layer.source.TileSource

import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.utils.io.discard
import kotlin.time.Duration.Companion.seconds

/**
 * Base for [TileSource] implementations that fetch bytes over HTTP. Owns one lazily-built
 * [HttpClient] so subclasses only have to provide a URL for `(z, x, y)`.
 *
 * The status interpretation is the contract all WorldWind tile sources share:
 *   * `2xx` non-empty body → [TileBlob] with `ETag` / `Last-Modified` captured.
 *   * `2xx` empty body → [TileBlob.EMPTY] (server explicitly returned no bytes).
 *   * `304` → `null` so the caller keeps its cached copy.
 *   * `404` → [TileBlob.EMPTY] (negative-cache sentinel).
 *   * Anything else → `error()` so the surrounding decorator's backoff loop fires.
 *
 * Subclasses translate `(z, x, y)` into a URL and call [fetchTileBlob]. They never need to
 * touch the client directly.
 */
abstract class HttpTileSource(
    val headers: Map<String, String> = emptyMap(),
    private val connectTimeoutMs: Long = 5.seconds.inWholeMilliseconds,
    private val requestTimeoutMs: Long = 30.seconds.inWholeMilliseconds,
    private val clientConfig: HttpClientConfig<*>.() -> Unit = {},
) : TileSource {

    protected val client: HttpClient by lazy {
        DefaultHttpClient(
            connectTimeout = connectTimeoutMs,
            requestTimeout = requestTimeoutMs,
            config = clientConfig,
        )
    }

    /**
     * GET [url] with conditional-request headers (when provided), interpret the status code
     * per the class contract, and return a [TileBlob] (or `null` for 304). [errorContext]
     * appears in the thrown error message for non-2xx/304/404 responses.
     */
    protected suspend fun fetchTileBlob(
        url: String,
        previousEtag: String?,
        previousLastModified: String?,
        errorContext: String,
    ): TileBlob? {
        val extraHeaders = headers
        val response = client.get(url) {
            for ((k, v) in extraHeaders) header(k, v)
            if (previousEtag != null) header("If-None-Match", previousEtag)
            if (previousLastModified != null) header("If-Modified-Since", previousLastModified)
        }
        val status = response.status.value
        if (status in 200..299) {
            val bytes = response.readRawBytes()
            return if (bytes.isEmpty()) TileBlob.EMPTY
            else TileBlob(
                bytes = bytes,
                etag = response.headers["ETag"],
                lastModified = response.headers["Last-Modified"],
                // Strip the charset / boundary parameter so consumers can match on the
                // base MIME (`application/bil16` rather than `application/bil16; charset=binary`).
                contentType = response.headers["Content-Type"]?.substringBefore(';')?.trim(),
            )
        }
        // Drain the non-2xx body so ktor releases the pooled connection (the branches below return/throw without reading it, leaking the call until GC); discard() allocates nothing, a 304 has no body, and keeping the connection poolable avoids an SSL re-handshake.
        response.bodyAsChannel().discard()
        return when (status) {
            304 -> null
            404 -> TileBlob.EMPTY
            else -> error("$errorContext fetch failed: HTTP $status for $url")
        }
    }

    override fun close() { client.close() }
}
