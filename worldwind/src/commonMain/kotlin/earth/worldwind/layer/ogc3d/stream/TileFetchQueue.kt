package earth.worldwind.layer.ogc3d.stream

import earth.worldwind.layer.cache.BlobStore
import earth.worldwind.layer.cache.NoOpBlobStore
import earth.worldwind.layer.ogc3d.TilesetSource
import earth.worldwind.layer.ogc3d.auth.TilesetAuthProvider
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * Bounded coroutine pool for tileset.json + content payload fetches. Runs the
 * [TilesetAuthProvider] rewrite before each GET; same path for root and per-tile.
 */
class TileFetchQueue(
    private val source: TilesetSource,
    parentScope: CoroutineScope,
    /** Max concurrent fetch + parse jobs. The permit wraps BlobStore/HTTP fetch through
     *  parse + texture decode + channel send end-to-end, so this is also the hard cap on
     *  parse-stage heap (decoded Bitmaps + vertex FloatArrays). 32 keeps cached LoD fill
     *  rate brisk on Photoreal (~250 tiles/sec) while still capping native heap at
     *  ~64 MB — below 32 the parse pool is the bottleneck on cached loads. */
    private val maxConcurrent: Int = 32,
    /** URI-keyed persistent cache. Default no-op = network-only. */
    private val blobStore: BlobStore = NoOpBlobStore,
    /** Fired during session recovery, after every in-flight old-session fetch has been
     *  cancelled and the auth provider's session state cleared. The layer drops its parsed
     *  tree so the next render re-fetches root and rebuilds under the fresh session. */
    private val onAuthExpired: (() -> Unit)? = null,
) {
    private val job: Job = SupervisorJob(parentScope.coroutineContext[Job])
    private val baseContext = parentScope.coroutineContext + job
    /** Orchestrates recovery; a child of [job] but never of an epoch, so it survives the
     *  epoch cancellation it performs. */
    private val controlScope = CoroutineScope(baseContext)
    private val permits = PrioritySemaphore(maxConcurrent)
    private val client: HttpClient by lazy { DefaultHttpClient() }
    @Volatile private var isShutdown: Boolean = false

    /** Every fetch runs in the current epoch's scope. A session rejection cancels the whole
     *  epoch at once (killing all dead-session downloads) and swaps in a fresh one; fetches
     *  carry the epoch they launched in, so a straggler rejection from a cancelled epoch is
     *  simply ignored — no latch, no race. */
    @Volatile private var epoch: Int = 0
    @Volatile private var epochScope = CoroutineScope(baseContext + SupervisorJob(job))
    /** Serializes recovery so concurrent rejections collapse to one epoch rotation. */
    private val recoveryMutex = Mutex()

    /** Once a session has gone stale, cached tileset.json bodies carry the dead session= in
     *  their child URLs, so skip the cache read for them (still write) and re-pull live;
     *  heavy .glb content stays cached. Set on first recovery, cleared next cold start. */
    @Volatile private var bypassTilesetCacheRead: Boolean = false

    /** Bounded fire-and-forget queue feeding one drain coroutine. Cache writes run there
     *  (single writer to SQLite, no inter-coroutine contention) so the permit pool never
     *  blocks on the SQLite write lock. Overflow drops; bytes still feed install. */
    private val cacheWriteQueue: Channel<CacheWriteRequest> =
        Channel(capacity = CACHE_WRITE_QUEUE_CAPACITY)

    init {
        // No NonCancellable: on shutdown the for-loop's receive throws and the drain exits;
        // pending writes drop (best-effort cache, fine to lose).
        controlScope.launch {
            for (req in cacheWriteQueue) {
                runCatching {
                    blobStore.put(
                        uri = req.uri,
                        bytes = req.bytes,
                        contentType = req.contentType,
                        etag = req.etag,
                    )
                }
            }
        }
    }

    private data class CacheWriteRequest(
        val uri: String,
        val bytes: ByteArray,
        val contentType: String?,
        val etag: String?,
    )

    /** Fetch a tileset.json (root or external); supports auth-provider redirects. */
    fun fetchTileset(
        url: String,
        onSuccess: (responseUrl: String, body: String) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Job {
        val launchEpoch = epoch
        return epochScope.launch {
        try {
            // Cache key strips the query string so per-session signed URLs (S3, Google
            // session=, Ion access_token=) don't rotate the cache key every session.
            val cacheKey = stableCacheKey(url)
            val cacheable = source.authProvider.isTilesetBodyCacheable(url)
            // Cache lookup throws (IDB schema mid-upgrade, transient transaction abort,
            // etc.) must not poison the tile — treat them as cache miss and fall through
            // to the network path. Without this, one IDB hiccup permanently FAILs the
            // tile, the traverser sees a FAILED root, and descent into children stops.
            val cached = if (cacheable && !bypassTilesetCacheRead) safeBlobStoreGet(blobStore, cacheKey) else null
            if (cached != null) {
                // Replay the captured response URL (carries the session token in its
                // query string) and the body itself through observeTilesetResponse so
                // the provider can restore in-memory state — Google's sessionToken,
                // Ion's per-asset access token — that the network path would set via
                // the same hook. Without this, cache hits leave the provider unaware of
                // the session and subsequent content fetches fail.
                val replayUrl = cached.responseUrl ?: url
                source.authProvider.observeTilesetResponse(url, replayUrl, cached.bytes.decodeToString())
                onSuccess(replayUrl, cached.bytes.decodeToString())
                return@launch
            }
            // Auth-provider-driven redirect loop (Cesium Ion asset envelope etc.).
            var currentUrl = url
            for (hop in 0..MAX_REDIRECT_HOPS) {
                val finalUrl: String
                val body: String
                val contentType: String?
                val etag: String?
                val status: HttpStatusCode
                permits.acquire(TILESET_FETCH_PRIORITY)
                try {
                    val authed = source.authProvider.rewriteRequest(currentUrl, mutableMapOf())
                    val response: HttpResponse = client.get(authed.url) {
                        authed.headers.forEach { (k, v) -> header(k, v) }
                    }
                    body = response.bodyAsText()
                    finalUrl = response.call.request.url.toString()
                    contentType = response.headers[HttpHeaders.ContentType]
                    etag = response.headers[HttpHeaders.ETag]
                    status = response.status
                } finally {
                    permits.release()
                }
                // Non-2xx bodies must NOT be cached — would poison BlobStore permanently.
                if (!status.isSuccess()) {
                    // A subtree tileset.json can be rejected first (Google delivers refinement
                    // as nested tilesets), so recover the session here too — not just on content.
                    if (source.authProvider.isAuthRejection(status.value)) recoverSession(launchEpoch, finalUrl)
                    onFailure(HttpStatusException(status.value, status.description, finalUrl))
                    return@launch
                }
                source.authProvider.observeTilesetResponse(url, finalUrl, body)
                val redirect = source.authProvider.redirectFor(finalUrl, body)
                if (redirect != null) {
                    if (hop == MAX_REDIRECT_HOPS) {
                        onFailure(IllegalStateException("Too many tileset redirects (> $MAX_REDIRECT_HOPS) from $url"))
                        return@launch
                    }
                    currentUrl = redirect
                    continue
                }
                // Skip cache on redirect chains (Ion endpoint → tileset.json) and on
                // bodies the provider declares uncacheable. finalUrl is recorded as
                // responseUrl so cache hits can replay it through observeTilesetResponse
                // and recover any session token embedded in the URL's query string.
                if (currentUrl == url && cacheable) {
                    runCatching {
                        blobStore.put(
                            uri = cacheKey,
                            bytes = body.encodeToByteArray(),
                            contentType = contentType,
                            etag = etag,
                            responseUrl = finalUrl,
                        )
                    }
                }
                onSuccess(finalUrl, body)
                return@launch
            }
        } catch (cancelled: CancellationException) {
            // Don't surface cancellation as failure (callers would permanently FAIL the tile).
            throw cancelled
        } catch (t: Throwable) {
            logMessage(
                Logger.WARN, "TileFetchQueue", "fetchTileset",
                "fetch failed for ${source.displayName}: $url", t
            )
            onFailure(t)
        }
        }
    }

    companion object {
        const val MAX_REDIRECT_HOPS = 5

        /** Caps transient memory at ~32 MB (each pending request pins ~50-500 KB of bytes);
         *  on overflow [fetchContent] drops the cache write and the tile re-fetches next time. */
        private const val CACHE_WRITE_QUEUE_CAPACITY: Int = 64
    }

    /** Fetch a tile content payload. Returns raw bytes for [ContentDispatcher] to route. */
    fun fetchContent(
        request: TileContentRequest,
        onSuccess: suspend (bytes: ByteArray) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Job {
        val launchEpoch = epoch
        return epochScope.launch {
        if (request.cancelled) return@launch
        // Bytes survive the inner finally so the cache-write enqueue runs post-permit.
        var fetchedBytesForCache: ByteArray? = null
        var fetchedContentTypeForCache: String? = null
        var fetchedEtagForCache: String? = null
        try {
            val cacheKey = stableCacheKey(request.contentUri)
            permits.acquire(request.priority)
            try {
                if (request.cancelled) return@launch
                val cached = safeBlobStoreGet(blobStore, cacheKey)
                // A nested subtree tileset.json arrives as tile content (kind TILESET_JSON).
                // During recovery its cached body would replay a stale session= and re-poison
                // the fresh one, so refetch those from the network; heavy .glb content still
                // serves from cache (its bytes are session-independent).
                val replayPoison = bypassTilesetCacheRead && cached != null &&
                    ContentDispatcher.detect(cached.bytes) == ContentDispatcher.Kind.TILESET_JSON
                if (cached != null && !replayPoison) {
                    onSuccess(cached.bytes)
                    return@launch
                }
                val authed = source.authProvider.rewriteRequest(request.contentUri, mutableMapOf())
                val response: HttpResponse = client.get(authed.url) {
                    authed.headers.forEach { (k, v) -> header(k, v) }
                }
                val bytes = response.readRawBytes()
                val contentType = response.headers[HttpHeaders.ContentType]
                val etag = response.headers[HttpHeaders.ETag]
                val status = response.status
                if (!status.isSuccess()) {
                    if (source.authProvider.isAuthRejection(status.value)) recoverSession(launchEpoch, request.contentUri)
                    onFailure(HttpStatusException(status.value, status.description, request.contentUri))
                    return@launch
                }
                // Stash for the post-permit cache-write enqueue; permit releases in the
                // inner finally so the next high-priority fetch can start its HTTP.
                fetchedBytesForCache = bytes
                fetchedContentTypeForCache = contentType
                fetchedEtagForCache = etag
            } finally {
                permits.release()
            }
            // Permit released — non-blocking trySend then onSuccess. Cache hit/failure paths
            // already return@launch'd above so [fetchedBytesForCache] is non-null here.
            val bytes = fetchedBytesForCache!!
            cacheWriteQueue.trySend(
                CacheWriteRequest(cacheKey, bytes, fetchedContentTypeForCache, fetchedEtagForCache)
            )
            if (request.cancelled) return@launch
            onSuccess(bytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            logMessage(
                Logger.WARN, "TileFetchQueue", "fetchContent",
                "fetch failed for ${request.contentUri}: ${t::class.simpleName}: ${t.message}",
            )
            onFailure(t)
        }
        }
    }

    /** Stop-the-world session recovery on an unauthenticated fetch from [rejectedEpoch]:
     *  rotate the epoch, cancel every in-flight old-session download, drop the dead session,
     *  then signal the layer. Rejections from an already-rotated epoch are ignored, so one
     *  expiry's flurry of failures collapses to a single rotation — no latch, no race. Runs on
     *  [controlScope] so cancelling the epoch can't cancel the recovery itself. */
    private fun recoverSession(rejectedEpoch: Int, failedUrl: String) {
        if (rejectedEpoch != epoch) return // superseded — a rotation already handled this expiry
        controlScope.launch {
            recoveryMutex.withLock {
                if (rejectedEpoch != epoch) return@withLock
                epoch++
                val dead = epochScope
                epochScope = CoroutineScope(baseContext + SupervisorJob(job))
                logMessage(
                    Logger.INFO, "TileFetchQueue", "auth",
                    "session recovery for ${source.displayName}: cancelling old-session fetches + re-auth (${stableCacheKey(failedUrl)})",
                )
                dead.coroutineContext[Job]?.cancelAndJoin() // kill all dead-session downloads
                bypassTilesetCacheRead = true               // stop serving cached dead-session tilesets
                runCatching { blobStore.remove(stableCacheKey(source.rootUri)) }
                runCatching { source.authProvider.invalidateSessionState() }
                onAuthExpired?.invoke()                     // layer refreshes under the fresh session
            }
        }
    }

    /** Path-only cache key. Strips the query string so per-session signed-URL params
     *  (S3 `X-Amz-Signature`, Google `session=`, Cesium Ion `access_token=`) don't rotate
     *  the cache key every session — the auth provider rebuilds them live each request. */
    private fun stableCacheKey(uri: String): String {
        val end = uri.indexOfAny(charArrayOf('?', '#'))
        return if (end >= 0) uri.substring(0, end) else uri
    }

    /** Cache lookup with throws treated as cache miss. A real failure of the lookup
     *  (web IDB transaction abort, GeoPackage IO error, etc.) shouldn't poison the tile
     *  — fall through to the network instead. */
    private suspend fun safeBlobStoreGet(store: BlobStore, key: String) = try {
        store.get(key)
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        logMessage(Logger.WARN, "TileFetchQueue", "safeBlobStoreGet",
            "blobStore.get('$key') failed; falling through to network: ${t::class.simpleName}: ${t.message}")
        null
    }

    /** Cancel in-flight requests + close the HTTP client. Idempotent. */
    fun shutdown() {
        if (isShutdown) return
        isShutdown = true
        job.cancel()
        client.close()
    }
}

/** Tileset.json fetches outrank tile content — without the root/sub-tileset metadata the
 *  per-tile priorities can't be computed, so they jump every waiting tile-content request. */
private const val TILESET_FETCH_PRIORITY: Double = Double.POSITIVE_INFINITY

/** Priority-ordered permit pool. [acquire] suspends until a permit is free; when multiple
 *  acquirers are waiting, [release] wakes the one with the highest [priority] (= largest
 *  Double value). Replaces `kotlinx.coroutines.sync.Semaphore` which is FIFO — under fast
 *  pan with hundreds of queued tile-fetch requests, FIFO leaves the camera-relevant tile
 *  buried behind 6+ seconds of stale-prior-frame requests. No in-flight cancellation:
 *  this just reorders the WAIT line, not the running set. Cesium 3D Tiles uses the same
 *  pattern in `Cesium.RequestScheduler.update()`.
 *
 *  Implementation: linear-insert + head-pop sorted list of waiters; O(N) per acquire,
 *  O(1) per release. For N up to ~1000 (observed at globe scale) the linear insert is
 *  microseconds — far cheaper than the multi-second queue stall it eliminates. */
private class PrioritySemaphore(private val maxPermits: Int) {
    private val mutex = Mutex()
    @Volatile private var available: Int = maxPermits
    private val waiters = ArrayDeque<Waiter>()

    private class Waiter(val priority: Double, val deferred: CompletableDeferred<Unit>)

    suspend fun acquire(priority: Double) {
        // Fast path or register-and-wait, decided under the mutex.
        val waiter: Waiter? = mutex.withLock {
            if (available > 0) {
                available--
                null
            } else {
                Waiter(priority, CompletableDeferred()).also { w ->
                    // Sorted descending by priority: head = highest.
                    var i = 0
                    while (i < waiters.size && waiters[i].priority >= priority) i++
                    waiters.add(i, w)
                }
            }
        }
        if (waiter == null) return  // fast path: got a permit immediately
        try {
            waiter.deferred.await()
        } catch (e: CancellationException) {
            // NonCancellable so we can clean up even though we ARE the cancelled coroutine.
            // If `waiters.remove` returns false, [release] already popped us off and delivered
            // the permit (its `deferred.complete` ran in the window between our cancel and our
            // resume) — we never claimed it, so hand it on to the next waiter via [release].
            // Without this, every cancel-during-wakeup leaks one permit; a fling-pan session
            // drains the pool and stalls every future fetch.
            withContext(NonCancellable) {
                val wokenButCancelled = mutex.withLock { !waiters.remove(waiter) }
                if (wokenButCancelled) release()
            }
            throw e
        }
    }

    /** Must be cancellation-safe — callers invoke from `finally` blocks of fetches that
     *  may already be cancelled. A bare `mutex.withLock` would throw immediately under
     *  the cancelled context, leaking the permit; running under `NonCancellable` lets the
     *  brief atomic update always finish. */
    suspend fun release() = withContext(NonCancellable) {
        // Loop in case the head waiter was cancelled between acquire-suspend and now —
        // skip dead waiters and either wake a live one or return the permit to the pool.
        while (true) {
            val woken = mutex.withLock {
                if (waiters.isEmpty()) {
                    available++
                    null
                } else waiters.removeFirst()
            } ?: return@withContext
            if (woken.deferred.complete(Unit)) return@withContext
        }
    }

    fun availablePermits(): Int = available
}

/** Non-2xx HTTP response. Distinct from IO errors so callers can decide retry policy. */
class HttpStatusException internal constructor(
    val statusCode: Int,
    val statusDescription: String,
    val url: String,
) : RuntimeException("HTTP $statusCode $statusDescription for $url")
