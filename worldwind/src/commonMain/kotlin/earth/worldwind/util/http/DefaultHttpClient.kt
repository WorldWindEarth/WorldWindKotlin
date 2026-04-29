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
