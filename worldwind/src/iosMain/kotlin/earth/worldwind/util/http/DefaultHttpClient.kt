package earth.worldwind.util.http

import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.*

@Suppress("FunctionName")
actual fun DefaultHttpClient(
    connectTimeout: Long,
    requestTimeout: Long,
    config: HttpClientConfig<*>.() -> Unit
) = HttpClient(Darwin) {
    config(this)
    install(HttpTimeout) {
        connectTimeoutMillis = connectTimeout
        requestTimeoutMillis = requestTimeout
        socketTimeoutMillis = requestTimeout
    }
}
