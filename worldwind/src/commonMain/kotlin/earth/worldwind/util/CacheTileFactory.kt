package earth.worldwind.util

import earth.worldwind.layer.cache.CacheEvictionPolicy
import kotlin.time.Instant

/**
 * Tile factory with cache support.
 */
interface CacheTileFactory : TileFactory {
    /**
     * Unique key of this layer in the cache
     */
    val contentKey: String
    /**
     * Path to cache content storage root
     */
    val contentPath: String

    /**
     * Last modified date of cache content
     */
    suspend fun lastModifiedDate(): Instant?

    /**
     * Estimated cache content size in bytes
     */
    suspend fun contentSize(): Long

    /** Caps applied by [evict]. Default unbounded; set via the ContentManager setup methods. */
    var evictionPolicy: CacheEvictionPolicy

    /** Sweep tiles that violate [evictionPolicy]. No-op on read-only / unbounded caches. */
    suspend fun evict()

    /**
     * Deletes all tiles from current cache content.
     *
     * @param deleteMetadata also delete cache metadata
     * @throws IllegalStateException In case of read-only database.
     */
    suspend fun clearContent(deleteMetadata: Boolean)
}