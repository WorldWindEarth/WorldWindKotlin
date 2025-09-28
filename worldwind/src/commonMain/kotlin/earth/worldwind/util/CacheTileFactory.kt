package earth.worldwind.util

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

    /**
     * Deletes all tiles from current cache content.
     *
     * @param deleteMetadata also delete cache metadata
     * @throws IllegalStateException In case of read-only database.
     */
    suspend fun clearContent(deleteMetadata: Boolean)
}