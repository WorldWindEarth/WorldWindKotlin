package earth.worldwind.layer.cache

import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.util.ContentManager
import kotlin.time.Instant

/**
 * Unified "one cached layer's content" view — size on disk, last-modified, clear/delete.
 *
 * Implemented by:
 *   * Self-contained file-format tile factories (`MBTileFactory`, `RMapsTileFactory`,
 *     `ATAKTileFactory`) where every operation is local to a single file.
 *   * The [ContentManager]-backed adapter returned by [ContentManager.contentEntry] for
 *     GPKG / IndexedDB / filesystem-cached layers where the catalog row lives in a
 *     [ContentManager] and ops fan out to its `findEntry` / `sizeBytesOf` / `clearEntry` /
 *     `deleteEntry`.
 *
 * App-side wrappers (display name, "size on disk" UI, "clear cache" buttons) can dispatch
 * generically against this surface instead of `when (tileFactory) { is MBTile -> ... is
 * RMaps -> ... else -> contentManager?.findEntry(key)?.lastModified }`.
 *
 * Mirrors the pre-refactor `CacheableImageLayer` surface area (`contentKey`,
 * `contentPath`, `lastModifiedDate`, `cacheContentSize`, `clearCache`) but is plain — no
 * `Layer` inheritance — so layer-typed and file-factory-typed code share one interface.
 */
interface ContentEntry {
    val contentKey: String
    val contentPath: String
    suspend fun lastModifiedDate(): Instant?
    suspend fun contentSize(): Long
    /**
     * Clear cached tiles for this entry. With [deleteMetadata] = `true`, also drop the
     * service-identity row (and, for file-format factories, the underlying file) so the
     * entry is gone for good. With `false` (default), the registration / catalog row
     * survives so re-download repopulates the same layer without re-configuration.
     */
    suspend fun clearEntry(deleteMetadata: Boolean = false)

    /**
     * Persist a user-visible name for this entry. Writes through to the underlying
     * catalog row when the implementation has one ([ContentManager]-backed entries);
     * no-op for self-contained file formats (MBTiles / RMaps / ATAK / Kropyva) where the
     * display name lives inside the immutable package and can't be reassigned from
     * outside.
     */
    suspend fun setDisplayName(displayName: String) = Unit
}

/**
 * Adapter that exposes one [ContentManager]-backed entry as a [ContentEntry]. Returns
 * `null` for [lastModifiedDate] when no catalog row exists; returns `0L` for [contentSize]
 * when no bytes are cached.
 */
private class ContentManagerEntry(
    private val contentManager: ContentManager,
    override val contentKey: String,
) : ContentEntry {
    override val contentPath: String get() = contentManager.pathName
    override suspend fun lastModifiedDate(): Instant? = contentManager.findEntry(contentKey)?.lastModified
    override suspend fun contentSize(): Long = contentManager.sizeBytesOf(contentKey)
    override suspend fun clearEntry(deleteMetadata: Boolean) {
        if (deleteMetadata) contentManager.deleteEntry(contentKey) else contentManager.clearEntry(contentKey)
    }
    override suspend fun setDisplayName(displayName: String) =
        contentManager.setDisplayName(contentKey, displayName)
}

/**
 * Wrap one [contentKey] inside this [ContentManager] as a [ContentEntry]. Mirrors
 * `findEntry(key)` for read-only inspection plus `clearEntry` / `deleteEntry` for
 * lifecycle, behind one uniform surface.
 */
fun ContentManager.contentEntry(contentKey: String): ContentEntry =
    ContentManagerEntry(this, contentKey)

/**
 * One-stop accessor for "this image layer's content view." Lookup order:
 *   1. The layer itself, when it implements [ContentEntry] directly (e.g. a third-party
 *      package layer that *is* its own cache, like Kropyva-style `.kme` packages).
 *   2. The layer's tile factory, when it implements [ContentEntry] (MBTiles, RMaps, ATAK).
 *   3. A [ContentManager]-backed adapter built from [contentManager] + the layer's
 *      [cachedSourceInfo] contentKey (GPKG / IDB-cached layers).
 *
 * Returns `null` when none of the above apply (network-only layer with no cache wired
 * and no [contentManager] supplied).
 */
fun TiledImageLayer.contentEntry(contentManager: ContentManager? = null): ContentEntry? {
    (this as? ContentEntry)?.let { return it }
    (tiledSurfaceImage?.tileFactory as? ContentEntry)?.let { return it }
    val key = cachedSourceInfo?.contentKey ?: return null
    return contentManager?.contentEntry(key)
}

/**
 * Elevation-coverage counterpart of [TiledImageLayer.contentEntry] — coverages have no
 * "self-contained file factory" case in the current factory set, so this is just the
 * [ContentManager] adapter behind a uniform call site.
 */
fun TiledElevationCoverage.contentEntry(contentManager: ContentManager? = null): ContentEntry? {
    val key = cachedSourceInfo?.contentKey ?: return null
    return contentManager?.contentEntry(key)
}
