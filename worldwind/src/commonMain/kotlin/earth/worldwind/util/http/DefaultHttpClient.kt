package earth.worldwind.util.http

import io.ktor.client.*
import io.ktor.client.plugins.*

/**
 * Returns a platform-configured Ktor [HttpClient]. JVM and Android use OkHttp; JS uses the
 * Ktor JS engine. Apps can install per-platform engine config (custom SSL, proxies) via the
 * platform-side `httpClientCustomizer` hook.
 */
@Suppress("FunctionName")
expect fun DefaultHttpClient(
    connectTimeout: Long = 3000L,
    requestTimeout: Long = 30000L,
    config: HttpClientConfig<*>.() -> Unit = {}
): HttpClient

/** Shared connect/request timeout defaults for feature-source HTTP clients (WFS, Shapefile, …),
 *  longer than [DefaultHttpClient]'s tile-fetch baseline because capability documents and bulk
 *  GML / feature responses can be large and slow to assemble server-side. */
internal object HttpDefaults {
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val REQUEST_TIMEOUT_MS = 120_000L
}
