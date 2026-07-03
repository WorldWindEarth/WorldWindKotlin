package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileSource

import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.CacheReadableElevationSourceFactory
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.globe.elevation.coverage.ElevationImage
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Cross-platform [ElevationSourceFactory] backed by an [ElevationStoreBackend] + an
 * optional upstream [TileSource]. Owns the source-format → storage-format transcoding
 * pipeline (encode/decode via [ElevationStorageCodec]) so platforms (JS, iOS) don't have
 * to reimplement it.
 *
 * Platform-specific bits are deliberately small:
 *   - [backend] handles "where do the bytes live" (IDB / filesystem / etc.).
 *   - [networkDecoder] handles "how to decode the wire format" (each platform has its own
 *     BIL/TIFF/DTED decode path).
 * The factory itself stays in commonMain.
 *
 * Used by `WebContentManager.createElevationSourceFactory` (JS) and
 * `IosContentManager.createElevationSourceFactory` (iOS). JVM keeps its own gpkg-bound
 * factory (`GpkgCachedElevationSourceFactory`) because the GeoPackage ancillary-table
 * layout doesn't fit the simple `(scale, offset)` shape the backend interface exposes.
 */
@OptIn(DelicateCoroutinesApi::class)
class CachedElevationSourceFactory(
    private val backend: ElevationStoreBackend,
    private val networkSource: TileSource?,
    private val networkDecoder: NetworkBytesDecoder,
    val outputFormat: String,
    val isFloat: Boolean,
    private val tileMatrixSet: TileMatrixSet,
    /** Stale-while-revalidate threshold (reuses eviction `staleAfter`); [Duration.INFINITE] = off. */
    private val staleAfter: Duration = Duration.INFINITE,
    /** Background scope for revalidation refreshes; fire-and-forget, outlives the read. */
    private val revalidationScope: CoroutineScope = GlobalScope,
) : ElevationSourceFactory, OfflineToggleable, CachedSourceInfoProvider,
    BulkRetrievableElevationSourceFactory, CacheReadableElevationSourceFactory, RevalidatingSource {
    override val contentType = "CachedElevation"
    override var isCacheOnly: Boolean = networkSource == null
    override val cacheInfo: CachedSourceInfo
        get() = backend.cacheInfo

    /** Invoked (off the render thread, with `(matrix ordinal, column, row)`) after a stale tile
     *  is re-downloaded; the coverage wires this to drop the cached array + redraw. */
    override var onTileRevalidated: ((z: Int, x: Int, y: Int) -> Unit)? = null

    private val revalidating = mutableSetOf<Long>()
    private val revalidateMutex = Mutex()

    /** After a cache hit, if the tile is older than [staleAfter], re-validate it in the background
     *  with a conditional GET (the stored ETag / Last-Modified): a `304` just bumps the freshness
     *  stamp (no re-decode, no redraw); a `200` re-downloads, re-stamps the validators, and triggers
     *  a redraw. No-op when offline, when freshness isn't tracked, or when a refresh for this tile is
     *  already in flight. */
    private suspend fun maybeRevalidate(z: Int, x: Int, y: Int, cached: CachedTile) {
        if (isCacheOnly || networkSource == null || staleAfter == Duration.INFINITE || cached.cachedAt == null) return
        if (Clock.System.now().toEpochMilliseconds() - cached.cachedAt <= staleAfter.inWholeMilliseconds) return
        val key = tileKey(z, x, y)
        if (!revalidateMutex.withLock { revalidating.add(key) }) return
        revalidationScope.launch {
            try {
                if (conditionalRevalidate(z, x, y, cached.etag, cached.lastModified)) onTileRevalidated?.invoke(z, x, y)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                log(WARN, "CachedElevation background refresh failed [$z/$x/$y]: ${t.message}")
            } finally {
                revalidateMutex.withLock { revalidating.remove(key) }
            }
        }
    }

    /** Conditional GET for [maybeRevalidate]: `304` (null blob) bumps only the freshness stamp and
     *  returns `false`; `200` decodes, transcodes, write-throughs with the fresh validators, and
     *  returns `true`. Transport errors propagate to the caller's catch (no false bump). */
    internal suspend fun conditionalRevalidate(z: Int, x: Int, y: Int, etag: String?, lastModified: String?): Boolean {
        val network = networkSource ?: return false
        val blob = network.fetchTile(z, x, y, etag, lastModified)
        if (blob == null) { // 304 Not Modified — bytes still current; restart the SWR window.
            backend.bumpValidatedAt(z, x, y)
            return false
        }
        if (blob.isEmpty || backend.isReadOnly) return false
        val networkType = blob.contentType?.takeUnless {
            it.equals("application/octet-stream", ignoreCase = true)
        } ?: outputFormat
        val decoded = try {
            networkDecoder.decodeNetworkBytes(blob.bytes, networkType)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            log(WARN, "CachedElevation revalidation decode failed [$z/$x/$y]: ${t.message}")
            return false
        } ?: return false
        val matrix = tileMatrixSet.entries.getOrNull(z) ?: return false
        val encoded = ElevationStorageCodec.encode(decoded, isFloat, matrix.tileWidth, matrix.tileHeight)
        backend.writeTile(z, x, y, encoded.bytes, encoded.tileScale, encoded.tileOffset, blob.etag, blob.lastModified)
        return true
    }

    override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource =
        ElevationSource.fromUnrecognized(CachedElevationRef(this, tileMatrix.ordinal, column, row))

    // Tile axis mapping mirrors createElevationSource: z = matrix ordinal, x = column, y = row.
    // Decode + the ElevationImage min/max/missing scan run off the render thread so the caller
    // resumes on the main thread with a ready image and pays a single coroutine switch per tile.
    override suspend fun readCachedTileImage(tileMatrix: TileMatrix, row: Int, column: Int): ElevationImage? =
        withContext(Dispatchers.Default) {
            readCachedTile(tileMatrix.ordinal, column, row)?.let { ElevationImage(it) }
        }

    /**
     * Bulk-download path: by default skip already-cached tiles, otherwise force a network
     * fetch and persist. **Bypasses [isCacheOnly]** — bulk is user-initiated and
     * orthogonal to the renderer's offline toggle. Returns `true` on cache hit /
     * successful fetch + write, `false` on cache miss + (no network / fetch failure /
     * decode failure). When [overrideCache] is true, the cache hit short-circuit is
     * skipped and every tile is re-downloaded.
     */
    override suspend fun fetchAndCacheTile(z: Int, x: Int, y: Int, overrideCache: Boolean): Boolean {
        if (!overrideCache && readCachedTile(z, x, y) != null) return true
        val network = networkSource ?: return false
        // Let transport errors propagate so the bulk-retrieval loop can retry with backoff.
        // Returning false here (as the renderer-facing fetchTile does) would make the loop
        // treat a transient failure as a permanent skip. `false` is reserved for genuinely
        // non-recoverable cases below: empty/404 tile and decode failure.
        val blob = network.fetchTile(z, x, y) ?: return false
        if (blob.isEmpty) return false
        val networkType = blob.contentType?.takeUnless {
            it.equals("application/octet-stream", ignoreCase = true)
        } ?: outputFormat
        val decoded = try {
            networkDecoder.decodeNetworkBytes(blob.bytes, networkType)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            log(WARN, "CachedElevation network decode failed [$z/$x/$y]: ${t.message}")
            return false
        } ?: return false
        if (backend.isReadOnly) return true
        val matrix = tileMatrixSet.entries.getOrNull(z) ?: return true
        return try {
            val encoded = ElevationStorageCodec.encode(decoded, isFloat, matrix.tileWidth, matrix.tileHeight)
            backend.writeTile(z, x, y, encoded.bytes, encoded.tileScale, encoded.tileOffset, blob.etag, blob.lastModified)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            log(WARN, "CachedElevation backend write failed [$z/$x/$y]: ${t.message}")
            true  // network round-trip succeeded; write failure is best-effort
        }
    }

    /**
     * Cache-only read: decode the tile from [backend] without any network I/O. Returns the
     * rendered `ShortArray` on a hit, or `null` on a miss / decode failure. A decode failure
     * (corrupted blob, transitional state) is treated as a miss so the tile isn't trapped
     * forever — the network path can repopulate it.
     */
    private suspend fun readCachedTile(z: Int, x: Int, y: Int): ShortArray? =
        readCachedTileWithMeta(z, x, y)?.first

    /** Like [readCachedTile] but also returns the stored [CachedTile] meta (cachedAt + the
     *  ETag/Last-Modified validators) for stale-while-revalidate. */
    private suspend fun readCachedTileWithMeta(z: Int, x: Int, y: Int): Pair<ShortArray, CachedTile>? {
        val cached = try {
            backend.readTile(z, x, y)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            log(WARN, "CachedElevation backend read failed [$z/$x/$y]: ${t.message}; falling through to network")
            null
        } ?: return null
        return try {
            val decoded = ElevationStorageCodec.decode(cached.bytes, isFloat, cached.tileScale, cached.tileOffset)
            tileBufferToShorts(decoded) to cached
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            log(WARN, "CachedElevation cache decode failed [$z/$x/$y]: ${t.message}; falling through to network")
            null
        }
    }

    /**
     * Fetch one tile through the cache. Reads the encoded blob from [backend]; on cache
     * miss falls through to [networkSource], decodes via [networkDecoder], transcodes
     * via [ElevationStorageCodec], persists to [backend], returns the rendered
     * `ShortArray`.
     *
     * Returns `null` for any cache + network failure — the caller (a platform-specific
     * `retrieveTileArray`) maps that to the elevation pipeline's `retrievalFailed`.
     */
    suspend fun fetchTile(z: Int, x: Int, y: Int): ShortArray? {
        readCachedTileWithMeta(z, x, y)?.let { (shorts, cached) ->
            maybeRevalidate(z, x, y, cached)  // serve cached now; refresh in background if stale
            return shorts
        }
        if (isCacheOnly) return null
        val network = networkSource ?: return null
        val blob = try {
            network.fetchTile(z, x, y)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            log(WARN, "CachedElevation network fetch failed [$z/$x/$y]: ${t.message}")
            return null
        } ?: return null
        if (blob.isEmpty) return null
        val networkType = blob.contentType?.takeUnless {
            it.equals("application/octet-stream", ignoreCase = true)
        } ?: outputFormat
        val decoded = try {
            networkDecoder.decodeNetworkBytes(blob.bytes, networkType)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            log(WARN, "CachedElevation network decode failed [$z/$x/$y]: ${t.message}")
            return null
        } ?: return null
        // Encode + persist (best-effort) before returning the rendered shorts. A write
        // failure shouldn't poison the current render — log and continue.
        if (!backend.isReadOnly) {
            val matrix = tileMatrixSet.entries.getOrNull(z)
            if (matrix != null) {
                try {
                    val encoded = ElevationStorageCodec.encode(decoded, isFloat, matrix.tileWidth, matrix.tileHeight)
                    backend.writeTile(z, x, y, encoded.bytes, encoded.tileScale, encoded.tileOffset, blob.etag, blob.lastModified)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    log(WARN, "CachedElevation backend write failed [$z/$x/$y]: ${t.message}")
                }
            }
        }
        return tileBufferToShorts(decoded)
    }

    private fun tileBufferToShorts(buffer: ElevationTileBuffer): ShortArray = when (buffer) {
        is ElevationTileBuffer.Shorts -> buffer.values
        is ElevationTileBuffer.Floats -> ShortArray(buffer.values.size) { i ->
            val v = buffer.values[i]
            if (v == Float.MAX_VALUE) Short.MIN_VALUE else v.roundToInt().toShort()
        }
    }
}

/**
 * Per-tile carrier emitted by [CachedElevationSourceFactory.createElevationSource]. Each
 * platform's `retrieveTileArray` detects this via `elevationSource.asUnrecognized()` and
 * dispatches into [factory] for the actual cache/network + transcoding flow.
 */
class CachedElevationRef(
    val factory: CachedElevationSourceFactory,
    val z: Int, val x: Int, val y: Int,
)

/**
 * Platform-specific wire-format decoder used by [CachedElevationSourceFactory.fetchTile]
 * on cache miss. Each platform implements the same MIME dispatch table (BIL16 / BIL32 /
 * TIFF / DTED → [ElevationTileBuffer]) — sharing the dispatch in commonMain would mean
 * porting every per-platform decoder kernel here, which buys little vs. the per-platform
 * footprint cost.
 */
fun interface NetworkBytesDecoder {
    suspend fun decodeNetworkBytes(bytes: ByteArray, contentType: String): ElevationTileBuffer?
}

/**
 * Mixin for cache-aware sources / factories that can flip between online and offline
 * modes at runtime. Lets app-level "go offline" walks dispatch generically across
 * [CachedTileSource] (image / vector / feature pyramids) and the elevation factories
 * (`CachedElevationSourceFactory`, `GpkgCachedElevationSourceFactory`).
 *
 * When [isCacheOnly] is `true`, fetch paths never call the upstream network source;
 * cache misses return `null` (or the layer's equivalent of "no data"). Implementations
 * should respond to runtime toggles without re-wiring.
 */
interface OfflineToggleable {
    var isCacheOnly: Boolean
}
