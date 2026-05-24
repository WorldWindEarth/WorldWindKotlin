package earth.worldwind.layer.cache

import io.ktor.client.fetch.fetch
import org.w3c.fetch.Response
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * Browser Cache API wrapper for tile HTTP requests. Image + elevation layers route through
 * here for URL prefixes that have a registered store; tiles persist as `(Request, Response)`
 * pairs — the right primitive for HTTP blobs (IDB is for the parsed feature rows).
 */
internal object WebTileCache {
    private val patterns = mutableListOf<Pair<String, String>>() // urlPrefix -> cache store name

    /** Register a URL prefix → cache store mapping. Longest-matching prefix wins on lookup. */
    fun register(urlPrefix: String, storeName: String) {
        patterns.removeAll { it.first == urlPrefix }
        patterns += urlPrefix to storeName
        patterns.sortByDescending { it.first.length }
    }

    fun unregister(storeName: String) {
        patterns.removeAll { it.second == storeName }
    }

    fun storeNameFor(url: String): String? = patterns.firstOrNull { url.startsWith(it.first) }?.second

    /**
     * Fetch [url] via Cache API store [storeName]. Returns the cached response on hit;
     * on miss, performs a network fetch and caches a clone of the response before returning it.
     */
    suspend fun fetchWithCache(url: String, storeName: String): Response {
        val cache = window.cacheStorage.open(storeName).await()
        val cached = cache.match(url).await()
        if (cached != null) return cached
        val response = fetch(url).await()
        if (response.ok) {
            // Clone before put — the response body is single-consume.
            cache.put(url, response.asDynamic().clone()).await()
        }
        return response
    }

    /** Drop the named cache store. Used by content-manager cache-clear paths. */
    suspend fun deleteStore(storeName: String) {
        window.cacheStorage.delete(storeName).await()
    }

    /** Sum of cached response Content-Length headers under [storeName]. Best-effort. */
    suspend fun storeSize(storeName: String): Long {
        if (!window.cacheStorage.has(storeName).await()) return 0L
        val cache = window.cacheStorage.open(storeName).await()
        val keys = cache.keys().await()
        var total = 0L
        for (request in keys) {
            val response = cache.match(request).await() ?: continue
            val len = response.headers.get("Content-Length")?.toLongOrNull()
            if (len != null) total += len
        }
        return total
    }
}

// Cache API external declarations. The browser provides `window.caches` but the JS Kotlin
// stdlib doesn't include typed bindings for it; minimal externals here cover the surface used.

internal external interface CacheStorage {
    fun open(cacheName: String): Promise<JsCache>
    fun has(cacheName: String): Promise<Boolean>
    fun delete(cacheName: String): Promise<Boolean>
}

internal external interface JsCache {
    fun match(request: Any): Promise<Response?>
    fun put(request: Any, response: dynamic): Promise<Unit>
    fun keys(): Promise<Array<Any>>
}

private external interface WindowWithCaches {
    val caches: CacheStorage
}

internal val org.w3c.dom.Window.cacheStorage: CacheStorage
    get() = this.unsafeCast<WindowWithCaches>().caches
