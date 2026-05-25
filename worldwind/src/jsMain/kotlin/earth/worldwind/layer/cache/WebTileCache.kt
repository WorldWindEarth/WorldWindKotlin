package earth.worldwind.layer.cache

import io.ktor.client.fetch.fetch
import org.w3c.fetch.Response
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.time.Duration

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
     * on miss, performs a network fetch and caches a clone of the response (stamped with
     * an `X-WW-Cache-Time` epoch-ms header so [evictByPolicy] can compute age without a
     * companion table).
     */
    suspend fun fetchWithCache(url: String, storeName: String): Response {
        val cache = window.cacheStorage.open(storeName).await()
        val cached = cache.match(url).await()
        if (cached != null) return cached
        val response = fetch(url).await()
        if (response.ok) {
            val stamped = stampResponse(response.clone())
            cache.put(url, stamped).await()
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

    /**
     * Apply [policy] to Cache API store [storeName]. Walks cached entries, reads the
     * `X-WW-Cache-Time` header for age, and deletes entries that fall outside the caps.
     * Cache API doesn't expose write times directly — the stamp header is our shim.
     */
    suspend fun evictByPolicy(storeName: String, policy: CacheEvictionPolicy) {
        if (policy.isUnbounded) return
        if (!window.cacheStorage.has(storeName).await()) return
        val cache = window.cacheStorage.open(storeName).await()
        val keys = cache.keys().await()
        if (keys.isEmpty()) return

        val now = js("Date.now()").unsafeCast<Double>()
        val maxAgeMs = if (policy.maxAge == Duration.INFINITE) Double.POSITIVE_INFINITY
        else policy.maxAge.inWholeMilliseconds.toDouble()
        val cutoff = now - maxAgeMs

        // Collect (request, cacheTime, sizeBytes) for sorting + cap math.
        data class Entry(val request: Any, val cacheTime: Double, val sizeBytes: Long)
        val entries = ArrayList<Entry>(keys.size)
        for (request in keys) {
            val response = cache.match(request).await() ?: continue
            val stamp = response.headers.get(CACHE_TIME_HEADER)?.toDoubleOrNull() ?: 0.0
            val size = response.headers.get("Content-Length")?.toLongOrNull() ?: 0L
            entries += Entry(request, stamp, size)
        }
        if (entries.isEmpty()) return

        // Sort newest-first so the keep window slices off the front by recency.
        entries.sortByDescending { it.cacheTime }
        val toDelete = mutableListOf<Any>()
        var kept = 0L
        var keptBytes = 0L
        for (e in entries) {
            val tooOld = e.cacheTime < cutoff
            val overCount = kept >= policy.maxEntries
            val overBytes = keptBytes + e.sizeBytes > policy.maxBytes
            if (tooOld || overCount || overBytes) toDelete += e.request
            else { kept++; keptBytes += e.sizeBytes }
        }
        for (req in toDelete) cache.delete(req).await()
    }

    private const val CACHE_TIME_HEADER = "X-WW-Cache-Time"

    /**
     * Build a new Response that mirrors [original] but adds the [CACHE_TIME_HEADER]. Done in JS
     * so we don't fight the Headers constructor's API surface — we just spread the original
     * Headers into a new instance and add one more entry.
     */
    private fun stampResponse(original: Response): Response {
        val now = js("Date.now()")
        return js(
            "new Response(original.body, {" +
                    "status: original.status," +
                    "statusText: original.statusText," +
                    "headers: (() => {" +
                            "const h = new Headers(original.headers);" +
                            "h.set('X-WW-Cache-Time', String(now));" +
                            "return h;" +
                    "})()" +
            "})"
        ).unsafeCast<Response>()
    }
}

