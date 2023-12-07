package earth.worldwind.globe.elevation

interface CacheSourceFactory : ElevationSourceFactory {
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