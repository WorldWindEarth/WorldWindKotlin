package earth.worldwind.util.http

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import okhttp3.Dispatcher

/**
 * Optional global hook letting platform code customize the OkHttp engine before [DefaultHttpClient]
 * builds its client (e.g. tutorial apps installing permissive SSL on outdated JDKs, or production
 * apps wiring a corporate proxy / custom truststore). Applied before any caller-supplied `config`
 * so callers can still override.
 */
var httpClientCustomizer: (HttpClientConfig<OkHttpConfig>.() -> Unit)? = null

@Suppress("FunctionName")
actual fun DefaultHttpClient(
    connectTimeout: Long,
    requestTimeout: Long,
    config: HttpClientConfig<*>.() -> Unit
) = HttpClient(OkHttp) {
    engine {
        // OkHttp's default Dispatcher caps concurrent requests at 64 total and 5 per host.
        // The per-host cap is the killer for 3D Tiles workloads where hundreds of small tiles
        // come from a single CDN host (tile.googleapis.com, *.googleusercontent.com, Cesium Ion).
        // HTTP/2 lets us multiplex many streams over a single TCP+TLS connection, so raising
        // these is essentially free in connection count while removing the artificial throttle
        // that was capping our fetch throughput at ~50/sec.
        config { dispatcher(Dispatcher().apply { maxRequests = 256; maxRequestsPerHost = 64 }) }
    }
    httpClientCustomizer?.invoke(this)
    config(this)
    install(HttpTimeout) {
        connectTimeoutMillis = connectTimeout
        requestTimeoutMillis = requestTimeout
        socketTimeoutMillis = requestTimeout
    }
}
