package earth.worldwind.layer.ogc3d.stream

import earth.worldwind.layer.cache.BlobStore
import earth.worldwind.layer.cache.NoOpBlobStore
import earth.worldwind.layer.ogc3d.TilesetSource
import earth.worldwind.layer.ogc3d.auth.TilesetAuthProvider
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.PrioritySemaphore
import earth.worldwind.util.traceAsyncBegin
import earth.worldwind.util.traceAsyncEnd
import earth.worldwind.util.traceCounter
import earth.worldwind.util.traceSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Byte transport. Default is HTTP; an [ArchiveTileByteSource] serves a local SLPK in place. */
    private val byteSource: TileByteSource = HttpTileByteSource(),
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

    // Diagnostic counters — Volatile for cross-thread visibility; minor races acceptable.
    @Volatile private var cacheHitCount: Long = 0
    @Volatile private var cacheMissCount: Long = 0
    @Volatile private var cancelledInQueueCount: Long = 0
    @Volatile private var cancelledHoldingPermitCount: Long = 0
    @Volatile private var cacheWriteDroppedCount: Long = 0
    @Volatile private var cacheWriteEnqueuedCount: Long = 0
    @Volatile private var cacheWriteDrainedCount: Long = 0

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
                // coerceAtLeast(0) shields the diagnostic counter from producer/consumer races.
                cacheWriteEnqueuedCount = (cacheWriteEnqueuedCount - 1).coerceAtLeast(0)
                traceCounter("tiles.cacheWriteQueueDepth", cacheWriteEnqueuedCount)
                traceSection("Tile.cacheWrite[${req.bytes.size}]") {
                    runCatching {
                        blobStore.put(
                            uri = req.uri,
                            bytes = req.bytes,
                            contentType = req.contentType,
                            etag = req.etag,
                        )
                    }
                }
                cacheWriteDrainedCount++
                traceCounter("tiles.cacheWriteDrained", cacheWriteDrainedCount)
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
        onSuccess: suspend (responseUrl: String, body: String) -> Unit,
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
            val cached = if (cacheable && !bypassTilesetCacheRead) traceSection("Tileset.cacheRead") { safeBlobStoreGet(blobStore, cacheKey) } else null
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
                val response: TileByteResponse
                permits.acquire(TILESET_FETCH_PRIORITY)
                try {
                    val authed = source.authProvider.rewriteRequest(currentUrl, mutableMapOf())
                    response = traceSection("Tileset.netGet") { byteSource.get(authed.url, authed.headers) }
                } finally {
                    permits.release()
                }
                finalUrl = response.finalUrl
                body = response.bytes.decodeToString()
                contentType = response.contentType
                etag = response.etag
                // Non-2xx bodies must NOT be cached — would poison BlobStore permanently.
                if (!response.isSuccess) {
                    // A subtree tileset.json can be rejected first (Google delivers refinement
                    // as nested tilesets), so recover the session here too — not just on content.
                    if (source.authProvider.isAuthRejection(response.statusCode)) recoverSession(launchEpoch, finalUrl)
                    onFailure(HttpStatusException(response.statusCode, response.statusMessage ?: "", finalUrl))
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
                    traceSection("Tileset.cacheWrite[${body.length}]") {
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
        val cookie = request.contentUri.hashCode()
        traceAsyncBegin("Tile.queue", cookie)
        return epochScope.launch {
        if (request.cancelled) { traceAsyncEnd("Tile.queue", cookie); return@launch }
        // Track cancellation site so we can close Tile.queue and bucket the cancellation.
        var queueEnded = false
        var acquired = false
        var cacheHit = false
        // Bytes survive the inner finally so the cache-write enqueue runs post-permit.
        var fetchedBytesForCache: ByteArray? = null
        var fetchedContentTypeForCache: String? = null
        var fetchedEtagForCache: String? = null
        try {
            val cacheKey = stableCacheKey(request.contentUri)
            try {
                permits.acquire(request.priority)
                acquired = true
            } catch (e: CancellationException) {
                if (!queueEnded) { traceAsyncEnd("Tile.queue", cookie); queueEnded = true }
                cancelledInQueueCount++
                traceCounter("tiles.cancelled_in_queue", cancelledInQueueCount)
                throw e
            }
            try {
                traceAsyncEnd("Tile.queue", cookie); queueEnded = true
                traceAsyncBegin("Tile.permitHold", cookie)
                traceCounter("tiles.permits.inUse", (maxConcurrent - permits.availablePermits()).toLong())
                traceAsyncBegin("Tile.http", cookie)
                if (request.cancelled) { traceAsyncEnd("Tile.http", cookie); return@launch }
                val cached = traceSection("Tile.cacheRead") { safeBlobStoreGet(blobStore, cacheKey) }
                // A nested subtree tileset.json arrives as tile content (kind TILESET_JSON).
                // During recovery its cached body would replay a stale session= and re-poison
                // the fresh one, so refetch those from the network; heavy .glb content still
                // serves from cache (its bytes are session-independent).
                val replayPoison = bypassTilesetCacheRead && cached != null &&
                    ContentDispatcher.detect(cached.bytes) == ContentDispatcher.Kind.TILESET_JSON
                if (cached != null && !replayPoison) {
                    traceAsyncEnd("Tile.http", cookie)
                    cacheHit = true
                    cacheHitCount++
                    traceCounter("tiles.cache_hits", cacheHitCount)
                    onSuccess(cached.bytes)
                    return@launch
                }
                cacheMissCount++
                traceCounter("tiles.cache_misses", cacheMissCount)
                val authed = source.authProvider.rewriteRequest(request.contentUri, mutableMapOf())
                val response = traceSection("Tile.netGet") { byteSource.get(authed.url, authed.headers) }
                traceAsyncEnd("Tile.http", cookie)
                if (!response.isSuccess) {
                    if (source.authProvider.isAuthRejection(response.statusCode)) recoverSession(launchEpoch, request.contentUri)
                    onFailure(HttpStatusException(response.statusCode, response.statusMessage ?: "", request.contentUri))
                    return@launch
                }
                // Stash for the post-permit cache-write enqueue; permit releases in the
                // inner finally so the next high-priority fetch can start its HTTP.
                fetchedBytesForCache = response.bytes
                fetchedContentTypeForCache = response.contentType
                fetchedEtagForCache = response.etag
            } finally {
                traceAsyncEnd("Tile.permitHold", cookie)
                permits.release()
            }
            traceCounter("tiles.permits.inUse", (maxConcurrent - permits.availablePermits()).toLong())
            // Permit released — non-blocking trySend then onSuccess. Cache hit/failure paths
            // already return@launch'd above so [fetchedBytesForCache] is non-null here.
            val bytes = fetchedBytesForCache!!
            val sent = cacheWriteQueue.trySend(
                CacheWriteRequest(cacheKey, bytes, fetchedContentTypeForCache, fetchedEtagForCache)
            ).isSuccess
            if (sent) {
                cacheWriteEnqueuedCount++
                traceCounter("tiles.cacheWriteQueueDepth", cacheWriteEnqueuedCount)
            } else {
                cacheWriteDroppedCount++
                traceCounter("tiles.cacheWriteDropped", cacheWriteDroppedCount)
            }
            if (request.cancelled) return@launch
            onSuccess(bytes)
        } catch (cancelled: CancellationException) {
            // Cancelled-with-permit wastes permit time; cancelled-in-queue (above) is cheap.
            if (acquired && !cacheHit) {
                cancelledHoldingPermitCount++
                traceCounter("tiles.cancelled_holding_permit", cancelledHoldingPermitCount)
            }
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

    /** Cancel in-flight requests + close the byte source (HTTP client / SLPK archive). Idempotent. */
    fun shutdown() {
        if (isShutdown) return
        isShutdown = true
        job.cancel()
        byteSource.close()
    }
}

/** Tileset.json fetches outrank tile content — without the root/sub-tileset metadata the
 *  per-tile priorities can't be computed, so they jump every waiting tile-content request. */
private const val TILESET_FETCH_PRIORITY: Double = Double.POSITIVE_INFINITY

/** Non-2xx HTTP response. Distinct from IO errors so callers can decide retry policy. */
class HttpStatusException internal constructor(
    val statusCode: Int,
    val statusDescription: String,
    val url: String,
) : RuntimeException("HTTP $statusCode $statusDescription for $url")
