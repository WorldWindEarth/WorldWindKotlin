package earth.worldwind.layer.ogc3d.stream

import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders

/** [TileByteSource] over ktor — the default HTTP transport, wrapping a lazily-built [DefaultHttpClient]. */
class HttpTileByteSource(
    clientFactory: () -> HttpClient = { DefaultHttpClient() },
) : TileByteSource {
    private val client: HttpClient by lazy(clientFactory)

    override fun handles(uri: String) = uri.startsWith("http://") || uri.startsWith("https://")

    override suspend fun get(uri: String, headers: Map<String, String>): TileByteResponse {
        val response: HttpResponse = client.get(uri) {
            headers.forEach { (k, v) -> header(k, v) }
        }
        return TileByteResponse(
            statusCode = response.status.value,
            bytes = response.readRawBytes(),
            finalUrl = response.call.request.url.toString(),
            contentType = response.headers[HttpHeaders.ContentType],
            etag = response.headers[HttpHeaders.ETag],
            statusMessage = response.status.description,
        )
    }

    override fun close() = client.close()
}
