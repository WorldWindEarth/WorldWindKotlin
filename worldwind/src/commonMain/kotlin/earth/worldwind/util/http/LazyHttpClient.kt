package earth.worldwind.util.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * A [DefaultHttpClient] built on first use and closed at most once — the shared "one client per
 * feature source" pattern (WFS, Shapefile, Overpass Buildings), which otherwise each re-implemented
 * the `lazy { … }` build plus an `isInitialized()`-guarded [close]. Reusing one client keeps the
 * underlying OkHttp/engine connection pool warm across a source's capability + feature requests
 * instead of allocating a fresh dispatcher/pool per request. [close] is idempotent and never builds
 * the client just to close it.
 */
internal class LazyHttpClient(
    connectTimeout: Long = HttpDefaults.CONNECT_TIMEOUT_MS,
    requestTimeout: Long = HttpDefaults.REQUEST_TIMEOUT_MS,
    config: HttpClientConfig<*>.() -> Unit = {},
) {
    private val delegate = lazy { DefaultHttpClient(connectTimeout, requestTimeout, config) }

    /** The client, constructed on first access. */
    val value: HttpClient get() = delegate.value

    /** Close the client if it was ever built; a no-op otherwise. */
    fun close() { if (delegate.isInitialized()) delegate.value.close() }
}
