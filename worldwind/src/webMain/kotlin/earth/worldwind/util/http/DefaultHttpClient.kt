package earth.worldwind.util.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout

@Suppress("FunctionName")
actual fun DefaultHttpClient(
    connectTimeout: Long,
    requestTimeout: Long,
    config: HttpClientConfig<*>.() -> Unit
) = HttpClient(Js) {
    config(this)
    install(HttpTimeout) {
        connectTimeoutMillis = connectTimeout
        requestTimeoutMillis = requestTimeout
        socketTimeoutMillis = requestTimeout
    }
}
