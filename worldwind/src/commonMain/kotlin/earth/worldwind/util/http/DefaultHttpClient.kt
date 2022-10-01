package earth.worldwind.util.http

import io.ktor.client.*
import io.ktor.client.plugins.*

/**
 * Returns new platform-dependent HTTP client instance configured by default.
 * In case some special configuration will be required on each platform, then make this function "expected".
 */
@Suppress("FunctionName")
fun DefaultHttpClient(
    connectTimeout: Long = 3000L,
    requestTimeout: Long = 30000L,
    config: HttpClientConfig<*>.() -> Unit = {}
) = HttpClient {
    config(this)

    install(HttpTimeout) {
        connectTimeoutMillis = connectTimeout
        requestTimeoutMillis = requestTimeout
    }
}
