package earth.worldwind.util.http

import io.ktor.client.*
import io.ktor.client.engine.js.*
import io.ktor.client.plugins.*

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
    }
}
