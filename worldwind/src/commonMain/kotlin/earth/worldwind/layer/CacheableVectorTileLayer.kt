package earth.worldwind.layer

import earth.worldwind.layer.cache.CacheEvictionPolicy
import kotlin.time.Instant

/**
 * Vector-tile counterpart to [CacheableFeatureLayer]. Vector tiles ship as opaque encoded
 * BLOBs (Mapbox Vector Tile protobuf today, GeoJSON tiles in principle); the natural cache
 * unit is the raw bytes the tile server delivered, not the decoded features — re-encoding
 * into [earth.worldwind.layer.cache.CachedGeometry] inflates storage 4–10× and can't
 * represent multipolygons/multilines that MVT carries.
 *
 * Bind via platform-specific `configureCache(...)` — `GpkgContentManager` on JVM/Android,
 * `WebContentManager` on JS. The GeoPackage path uses the standard Image Matters Vector
 * Tiles community extension (`data_type='vector-tiles'`, `im_vector_tiles_mapbox`), so
 * caches we write are readable by QGIS, ogr2ogr, and other vector-tile-aware tooling.
 */
interface CacheableVectorTileLayer : Layer {
    var cacheTileFactory: VectorTileCacheSourceFactory?

    val isCacheConfigured get() = cacheTileFactory != null
    val contentKey get() = cacheTileFactory?.contentKey
    val contentPath get() = cacheTileFactory?.contentPath
    suspend fun lastModifiedDate(): Instant? = cacheTileFactory?.lastModifiedDate()
    suspend fun cacheContentSize(): Long = cacheTileFactory?.contentSize() ?: 0L

    @Throws(IllegalStateException::class)
    suspend fun clearCache(deleteMetadata: Boolean = false) {
        cacheTileFactory?.clearContent(deleteMetadata)
        if (deleteMetadata) disableCache()
    }

    fun disableCache() { cacheTileFactory = null }
}

/**
 * Cache backing for a [CacheableVectorTileLayer]: GeoPackage on JVM/Android, IndexedDB on
 * JS. Tiles are stored as raw encoded bytes keyed by `(z, x, y)`; HTTP revalidation
 * metadata (ETag, Last-Modified) is persisted alongside so the layer can issue conditional
 * requests on refresh.
 *
 * Empty-tile sentinel: a zero-byte [VectorTileBlob.bytes] means "fetched but empty" (HTTP
 * 404 from a tile server omitting ocean-only tiles, etc.) — distinct from null which means
 * "never fetched, go fetch it."
 */
interface VectorTileCacheSourceFactory {
    val contentType: String
    val contentKey: String
    val contentPath: String
    suspend fun lastModifiedDate(): Instant?
    suspend fun contentSize(): Long

    /** Caps applied by [evict]. Default unbounded; set via the ContentManager setup methods. */
    var evictionPolicy: CacheEvictionPolicy

    /** Sweep tiles that violate [evictionPolicy]. No-op on read-only / unbounded caches. */
    suspend fun evict()

    @Throws(IllegalStateException::class)
    suspend fun clearContent(deleteMetadata: Boolean)

    /** Read the cached tile for `(z, x, y)`. Returns null when never fetched. */
    suspend fun readTileBlob(z: Int, x: Int, y: Int): VectorTileBlob?

    /** Persist [bytes] (use empty array for fetched-empty) plus optional revalidation metadata. */
    @Throws(IllegalStateException::class)
    suspend fun writeTileBlob(
        z: Int, x: Int, y: Int,
        bytes: ByteArray, etag: String? = null, lastModified: String? = null,
    )
}

/**
 * One cached vector tile. [bytes] holds the encoded payload (MVT protobuf, possibly
 * gzipped) as delivered by the source — gzip-decoding happens on the HTTP client side, the
 * cache treats the bytes opaquely. Empty array = the source reported a tile-shaped hole.
 */
data class VectorTileBlob(
    val bytes: ByteArray,
    val etag: String? = null,
    val lastModified: String? = null,
) {
    val isEmpty: Boolean get() = bytes.isEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorTileBlob) return false
        return bytes.contentEquals(other.bytes) && etag == other.etag && lastModified == other.lastModified
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + (etag?.hashCode() ?: 0)
        result = 31 * result + (lastModified?.hashCode() ?: 0)
        return result
    }
}
