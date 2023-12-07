package earth.worldwind.util

/**
 * Tile factory with cache support.
 */
interface CacheTileFactory : TileFactory {
    /**
     * Allows saving new content to cache
     */
    var isWritable: Boolean

    /**
     * Deletes all tiles from current cache content.
     *
     * @param deleteMetadata also delete cache metadata
     * @throws IllegalStateException In case of read-only database.
     */
    suspend fun clearContent(deleteMetadata: Boolean)
}