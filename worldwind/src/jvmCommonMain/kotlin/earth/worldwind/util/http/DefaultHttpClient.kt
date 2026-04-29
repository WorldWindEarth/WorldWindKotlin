package earth.worldwind.util.http

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*

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
    httpClientCustomizer?.invoke(this)
    config(this)
    install(HttpTimeout) {
        connectTimeoutMillis = connectTimeout
        requestTimeoutMillis = requestTimeout
    }
}
