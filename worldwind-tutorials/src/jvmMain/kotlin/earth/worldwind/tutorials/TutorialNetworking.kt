package earth.worldwind.tutorials

import earth.worldwind.util.http.httpClientCustomizer
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Tutorials reach NASA's NEO WMS, DLR's WMTS, USGS WCS, etc. — public services with valid
 * certificates that nonetheless fail PKIX path-building on JDKs whose `cacerts` truststore
 * is outdated (older Adoptium/Zulu/Oracle 17 builds especially). Production apps should
 * keep their JDK current; tutorial apps install a trust-all OkHttp client into the engine's
 * [httpClientCustomizer] hook so demos run on whatever JDK the user happens to have.
 *
 * Calling this is INSECURE — any HTTPS endpoint is accepted regardless of certificate. Keep
 * it scoped to tutorial / development entry points.
 */
fun installPermissiveSslForTutorials() {
    val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    })
    val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
    val okHttp = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()
    httpClientCustomizer = {
        engine { preconfigured = okHttp }
    }
}
