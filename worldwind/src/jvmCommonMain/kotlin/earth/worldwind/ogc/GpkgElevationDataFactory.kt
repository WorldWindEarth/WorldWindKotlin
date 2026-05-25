package earth.worldwind.ogc

import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.CacheReadableElevationSourceFactory
import earth.worldwind.globe.elevation.ElevationDecoder
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.layer.cache.BulkRetrievableElevationSourceFactory
import earth.worldwind.layer.cache.CachedSourceInfo
import earth.worldwind.layer.cache.CachedSourceInfoProvider
import earth.worldwind.layer.cache.OfflineToggleable
import earth.worldwind.layer.source.TileSource
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GpkgContent
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log
import java.nio.Buffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Elevation source factory that pairs a [GeoPackage] cache with an optional upstream
 * [TileSource]. Owns the source-format → storage-format transcoding end-to-end.
 *
 * On cache miss the factory fetches bytes from [networkSource] (decoded via [outputFormat])
 * and writes them into the gpkg in the storage layout dictated by [isFloat]:
 *
 *   - `isFloat = true`  → TIFF-Float32 blob, `gpkg_2d_gridded_tile_ancillary.scale = 1`,
 *                         `offset = 0`. Lossless for Float32 sources; widening for Int16
 *                         sources.
 *   - `isFloat = false` → PNG-Int16 blob with per-tile `(scale, offset)` *packed* from the
 *                         in-memory `FloatBuffer` directly — the lossy step is the 16-bit
 *                         quantization (`(max - min) / 65535` per code), never a
 *                         WorldWind `ShortArray` rounding step. Int16 sources are written
 *                         as-is with `scale = 1, offset = 0`.
 *
 * Read path is the inverse: PNG with non-trivial scale/offset decodes to a `FloatBuffer`
 * preserving the storage precision; PNG with `(1, 0)` decodes to a `ShortBuffer`;
 * TIFF-Float32 decodes to a `FloatBuffer`. The single `ShortArray` conversion that
 * WorldWind's render pipeline requires happens downstream in
 * [ElevationDecoder.decodeBuffer], not inside this factory.
 *
 * Set [networkSource] = `null` for offline replay (cache hits served, misses return null).
 */
class GpkgCachedElevationSourceFactory(
    internal val geoPackage: GeoPackage,
    internal val content: GpkgContent,
    internal val networkSource: TileSource?,
    internal val outputFormat: String,
    internal val isFloat: Boolean,
    internal val tileMatrixSet: TileMatrixSet,
    internal val elevationDecoder: ElevationDecoder = ElevationDecoder(),
) : ElevationSourceFactory, OfflineToggleable, CachedSourceInfoProvider,
    BulkRetrievableElevationSourceFactory, CacheReadableElevationSourceFactory {
    override val contentType = "GpkgCachedElevation"

    override val cacheInfo: CachedSourceInfo
        get() = CachedSourceInfo(contentKey = content.tableName, contentPath = geoPackage.pathName)

    /** Bulk-download path: bypass [isCacheOnly] and force a network fetch + persist on
     *  cache miss. When [overrideCache] is true, also skip the cache hit short-circuit
     *  so every tile is re-downloaded. See
     *  [earth.worldwind.layer.cache.CachedElevationSourceFactory.fetchAndCacheTile]
     *  for the motivating contract. */
    override suspend fun fetchAndCacheTile(z: Int, x: Int, y: Int, overrideCache: Boolean): Boolean =
        GpkgCachedElevationDataFactory(this, z, x, y)
            .fetchElevationDataIgnoringCacheOnly(overrideCache = overrideCache) != null

    // Tile axis mapping mirrors createElevationSource: z = matrix ordinal, x = column, y = row.
    override suspend fun readCachedTileArray(tileMatrix: TileMatrix, row: Int, column: Int): ShortArray? {
        val buffer = GpkgCachedElevationDataFactory(this, tileMatrix.ordinal, column, row)
            .fetchCachedElevationData() ?: return null
        return elevationDecoder.bufferToShortArray(buffer)
    }

    /**
     * When `true`, the factory never calls [networkSource] — cache hits are served as
     * usual; cache misses return `null` from `fetchElevationData`. Toggleable at runtime
     * so a single layer can flip between online and offline modes without re-wiring its
     * source factory. Shares the [earth.worldwind.layer.cache.OfflineToggleable] interface
     * with [earth.worldwind.layer.cache.CachedTileSource] so app-level offline toggles
     * dispatch generically.
     */
    override var isCacheOnly: Boolean = networkSource == null

    override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource =
        ElevationSource.fromElevationDataFactory(
            GpkgCachedElevationDataFactory(this, tileMatrix.ordinal, column, row)
        )
}

/**
 * Per-tile implementation of the [ElevationSource.ElevationDataFactory] contract. Lives
 * for a single `(z, x, y)` read.
 *
 * `fetchElevationData()` is the only entry point — it tries the gpkg first, falls through
 * to [GpkgCachedElevationSourceFactory.networkSource] on a miss, and write-through-encodes
 * the network buffer to the gpkg in the storage layout chosen at coverage creation.
 */
open class GpkgCachedElevationDataFactory(
    protected val parent: GpkgCachedElevationSourceFactory,
    protected val zoomLevel: Int,
    protected val tileColumn: Int,
    protected val tileRow: Int,
) : ElevationSource.ElevationDataFactory {

    override suspend fun fetchElevationData(): Buffer? =
        fetchElevationData(ignoreCacheOnly = false, overrideCache = false)

    /** Bulk-download entry point: same as [fetchElevationData] but bypasses
     *  [GpkgCachedElevationSourceFactory.isCacheOnly]. When [overrideCache] is true, also
     *  skip the cache hit short-circuit so every tile is re-downloaded. See
     *  [earth.worldwind.layer.cache.CachedElevationSourceFactory.fetchAndCacheTile]. */
    internal suspend fun fetchElevationDataIgnoringCacheOnly(overrideCache: Boolean = false): Buffer? =
        fetchElevationData(ignoreCacheOnly = true, overrideCache = overrideCache)

    private suspend fun fetchElevationData(ignoreCacheOnly: Boolean, overrideCache: Boolean): Buffer? {
        // Cache decode is best-effort: corrupted bytes (transitional gpkg from a pre-
        // transcoding v3 cache run, or external tampering) shouldn't trap the tile in a
        // permanent retrievalFailed state. Treat any decode failure as a cache miss and
        // let the network path overwrite with fresh, correctly-transcoded bytes.
        if (!overrideCache) {
            try {
                readFromCache()?.let { return it }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                log(
                    WARN,
                    "GpkgCachedElevation cache decode failed [$zoomLevel/$tileColumn/$tileRow]: " +
                        "${t.message}; falling through to network to repopulate",
                )
            }
        }
        if (!ignoreCacheOnly && parent.isCacheOnly) return null
        val networkSource = parent.networkSource ?: return null
        val blob = try {
            networkSource.fetchTile(zoomLevel, tileColumn, tileRow)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            log(WARN, "GpkgCachedElevation network fetch failed [$zoomLevel/$tileColumn/$tileRow]: ${t.message}")
            return null
        } ?: return null
        if (blob.isEmpty) return null
        val decoded = decodeNetworkBlob(blob) ?: return null
        runCatching { writeToCache(decoded) }.onFailure {
            log(WARN, "GpkgCachedElevation cache write failed [$zoomLevel/$tileColumn/$tileRow]: ${it.message}")
        }
        // The cached encode/decode flow rewinds buffers; pass through the original
        // post-write position. Render consumers iterate via `Buffer.get(short[])` which
        // honors current position — rewind defensively.
        decoded.rewind()
        return decoded
    }

    /**
     * Cache-only read (no network): the decoded [Buffer] on a cache hit, or `null` on a miss
     * or decode failure. Lets a coverage serve cached tiles without consuming a network
     * retrieval slot. Mirrors the best-effort decode handling in [fetchElevationData].
     */
    suspend fun fetchCachedElevationData(): Buffer? = try {
        readFromCache()
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        log(
            WARN,
            "GpkgCachedElevation cache decode failed [$zoomLevel/$tileColumn/$tileRow]: ${t.message}",
        )
        null
    }

    /** Read from gpkg + ancillary metadata; return `null` if no row exists. */
    protected open suspend fun readFromCache(): Buffer? {
        val tileUserData = parent.geoPackage.readTileUserData(parent.content, zoomLevel, tileColumn, tileRow)
            ?: return null
        val bytes = tileUserData.tileData
        if (bytes.isEmpty()) return null
        val griddedTile = parent.geoPackage.readGriddedTile(parent.content, tileUserData)
        val griddedCoverage = parent.geoPackage.getGriddedCoverage(parent.content)
        return if (parent.isFloat) {
            parent.elevationDecoder.decodeTiff(bytes)
        } else {
            parent.elevationDecoder.decodePng(
                bytes,
                tileScale = griddedTile?.scale?.toFloat() ?: 1.0f,
                tileOffset = griddedTile?.offset?.toFloat() ?: 0.0f,
                coverageScale = griddedCoverage?.scale?.toFloat() ?: 1.0f,
                coverageOffset = griddedCoverage?.offset?.toFloat() ?: 0.0f,
                dataNull = griddedCoverage?.dataNull?.toFloat(),
            )
        }
    }

    /** Decode network bytes per [GpkgCachedElevationSourceFactory.outputFormat] (or the
     *  server's `Content-Type` when it advertises a specific elevation MIME). */
    protected open suspend fun decodeNetworkBlob(blob: earth.worldwind.layer.source.TileBlob): Buffer? {
        val serverType = blob.contentType
        val effectiveType =
            if (serverType == null || serverType.equals("application/octet-stream", ignoreCase = true))
                parent.outputFormat
            else serverType
        return parent.elevationDecoder.decodeElevationBytes(blob.bytes, effectiveType)
    }

    /** Write [buffer] to gpkg in the storage layout chosen at coverage creation. */
    protected open suspend fun writeToCache(buffer: Buffer) {
        if (parent.geoPackage.isReadOnly) return
        val matrix = parent.tileMatrixSet.entries.getOrNull(zoomLevel) ?: return
        val encoded = encodeForStorage(buffer, matrix.tileWidth, matrix.tileHeight) ?: return
        parent.geoPackage.writeTileUserData(
            parent.content, zoomLevel, tileColumn, tileRow, encoded.bytes,
        )
        parent.geoPackage.writeGriddedTile(
            parent.content, zoomLevel, tileColumn, tileRow,
            scale = encoded.tileScale, offset = encoded.tileOffset,
        )
    }

    /**
     * Encode [buffer] for storage in the gpkg, returning the bytes plus the per-tile
     * (scale, offset) that need to be recorded in `gpkg_2d_gridded_tile_ancillary` so the
     * read path can recover the original values.
     *
     * Critically, **never** rounds Float32 through a WorldWind `ShortArray`: the
     * Float→Int16 path uses scale/offset packing directly off the [FloatBuffer], so the
     * 16-bit quantization is the only lossy step.
     */
    protected open fun encodeForStorage(buffer: Buffer, tileWidth: Int, tileHeight: Int): EncodedTile? = when (buffer) {
        is ShortBuffer -> encodeShortBuffer(buffer, tileWidth, tileHeight)
        is FloatBuffer -> encodeFloatBuffer(buffer, tileWidth, tileHeight)
        else -> null
    }

    private fun encodeShortBuffer(buffer: ShortBuffer, tileWidth: Int, tileHeight: Int): EncodedTile {
        buffer.rewind()
        return if (parent.isFloat) {
            // Widen Int16 → Float32 directly (lossless) and TIFF-encode.
            val floats = FloatArray(buffer.remaining()) { buffer.get().toFloat() }
            buffer.rewind()
            EncodedTile(
                bytes = parent.elevationDecoder.encodeTiff(FloatBuffer.wrap(floats), tileWidth, tileHeight),
                tileScale = 1.0f,
                tileOffset = 0.0f,
            )
        } else {
            // Lossless PNG-Int16.
            val bytes = parent.elevationDecoder.encodePng(buffer, tileWidth, tileHeight)
            buffer.rewind()
            EncodedTile(bytes = bytes, tileScale = 1.0f, tileOffset = 0.0f)
        }
    }

    private fun encodeFloatBuffer(buffer: FloatBuffer, tileWidth: Int, tileHeight: Int): EncodedTile {
        buffer.rewind()
        if (parent.isFloat) {
            // TIFF-Float32 (lossless).
            return EncodedTile(
                bytes = parent.elevationDecoder.encodeTiff(buffer.duplicate(), tileWidth, tileHeight),
                tileScale = 1.0f,
                tileOffset = 0.0f,
            )
        }
        // Float32 → PNG-Int16 via per-tile (scale, offset) packing.
        // First pass: find non-null min/max in the FloatBuffer.
        val remaining = buffer.remaining()
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        var hasValid = false
        val temp = FloatArray(remaining)
        buffer.get(temp)
        for (f in temp) {
            if (f == Float.MAX_VALUE || f.isNaN()) continue
            hasValid = true
            if (f < min) min = f
            if (f > max) max = f
        }
        // Degenerate cases: all-null tile or flat tile. Pack with scale = 1, offset = min
        // so the codes are 0 and the decoder recovers `min` (or zero) for every cell.
        val effectiveMin = if (hasValid) min else 0.0f
        val effectiveMax = if (hasValid && max > min) max else effectiveMin
        val range = effectiveMax - effectiveMin
        val tileScale = if (range > 0.0f) range / 65535.0f else 1.0f
        val tileOffset = effectiveMin
        // Pack: code = round((f - offset) / scale), clamped to 0..65535.
        // Null sentinel: Float.MAX_VALUE maps to code 0 (consistent with `effectiveMin`
        // mapping there too — the only lossy null handling, but the cache contract is
        // "valid measurements"; nulls become floor-level on round-trip).
        val packed = ShortArray(remaining)
        for (i in 0 until remaining) {
            val f = temp[i]
            val code = if (f == Float.MAX_VALUE || f.isNaN()) {
                0
            } else {
                val raw = ((f - tileOffset) / tileScale).toDouble()
                val clamped = raw.coerceIn(0.0, 65535.0)
                kotlin.math.round(clamped).toInt()
            }
            // PNG writer takes the low 16 bits of scanline[i]. Storing as signed Short
            // round-trips correctly because the reader interprets the row as 16-bit
            // unsigned and we mask back to the same 16 bits on decode.
            packed[i] = code.toShort()
        }
        val bytes = parent.elevationDecoder.encodePng(ShortBuffer.wrap(packed), tileWidth, tileHeight)
        return EncodedTile(bytes = bytes, tileScale = tileScale, tileOffset = tileOffset)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GpkgCachedElevationDataFactory) return false
        return parent.content.tableName == other.parent.content.tableName &&
            zoomLevel == other.zoomLevel &&
            tileColumn == other.tileColumn &&
            tileRow == other.tileRow
    }

    override fun hashCode(): Int {
        var result = parent.content.tableName.hashCode()
        result = 31 * result + zoomLevel
        result = 31 * result + tileColumn
        result = 31 * result + tileRow
        return result
    }

    override fun toString() =
        "GpkgCachedElevationDataFactory(tableName=${parent.content.tableName}, z=$zoomLevel, x=$tileColumn, y=$tileRow)"

    /** Bytes ready for `writeTileUserData` plus the per-tile scale/offset to record in
     *  `gpkg_2d_gridded_tile_ancillary`. */
    protected data class EncodedTile(
        val bytes: ByteArray,
        val tileScale: Float,
        val tileOffset: Float,
    )
}
