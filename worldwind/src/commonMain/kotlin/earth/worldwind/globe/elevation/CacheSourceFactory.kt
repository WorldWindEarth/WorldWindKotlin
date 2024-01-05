package earth.worldwind.globe.elevation

import earth.worldwind.geom.Sector
import kotlinx.datetime.Instant

interface CacheSourceFactory : ElevationSourceFactory {
    /**
     * Unique key of this layer in the cache
     */
    val contentKey: String
    /**
     * Bounding sector of cache content or null, if it is not specified
     */
    val boundingSector: Sector?
    /**
     * Last update date of cache content
     */
    val lastUpdateDate: Instant

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