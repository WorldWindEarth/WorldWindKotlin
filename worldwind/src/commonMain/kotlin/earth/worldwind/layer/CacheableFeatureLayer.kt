package earth.worldwind.layer

import earth.worldwind.layer.cache.CachedFeatureRow
import kotlin.time.Instant

/**
 * Vector-feature counterpart to [CacheableImageLayer]. Bind via platform-specific
 * `configureCache(...)` — GpkgContentManager on JVM/Android, WebContentManager on JS.
 */
interface CacheableFeatureLayer : Layer {
    var cacheSourceFactory: FeatureCacheSourceFactory?

    val isCacheConfigured get() = cacheSourceFactory != null
    val contentKey get() = cacheSourceFactory?.contentKey
    val contentPath get() = cacheSourceFactory?.contentPath
    suspend fun lastModifiedDate(): Instant? = cacheSourceFactory?.lastModifiedDate()
    suspend fun cacheContentSize(): Long = cacheSourceFactory?.contentSize() ?: 0L

    /** Drop all cached features, optionally also removing the cache metadata row. */
    @Throws(IllegalStateException::class)
    suspend fun clearCache(deleteMetadata: Boolean = false) {
        cacheSourceFactory?.clearContent(deleteMetadata)
        if (deleteMetadata) disableCache()
    }

    fun disableCache() {
        cacheSourceFactory = null
    }
}

/**
 * Cache backing for a [CacheableFeatureLayer]: GeoPackage on JVM/Android, IndexedDB on JS.
 * Two access patterns share one schema — full-refresh (WFS, Shapefile) and per-tile (OSM, MVT).
 */
interface FeatureCacheSourceFactory {
    val contentType: String
    val contentKey: String
    val contentPath: String
    suspend fun lastModifiedDate(): Instant?
    suspend fun contentSize(): Long

    @Throws(IllegalStateException::class)
    suspend fun clearContent(deleteMetadata: Boolean)

    /** Read rows for tile `(z, x, y)`. Empty = never fetched; one null-geom row = fetched-empty. */
    suspend fun readFeatureTile(z: Int, x: Int, y: Int): List<CachedFeatureRow>

    /** Replace every row for one tile in a single transaction. Empty [rows] writes a sentinel. */
    @Throws(IllegalStateException::class)
    suspend fun writeFeatureTile(z: Int, x: Int, y: Int, rows: List<CachedFeatureRow>)

    /** Read every cached row (bulk-refresh sources). */
    suspend fun readAllFeatures(): List<CachedFeatureRow>

    /** Replace every cached row in one transaction (bulk-refresh sources). */
    @Throws(IllegalStateException::class)
    suspend fun replaceAllFeatures(rows: List<CachedFeatureRow>)
}
