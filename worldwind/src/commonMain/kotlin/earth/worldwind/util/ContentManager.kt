package earth.worldwind.util

import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.layer.cache.CacheEvictionPolicy
import earth.worldwind.layer.cache.CacheEntry
import earth.worldwind.layer.cache.FeatureStore
import earth.worldwind.layer.cache.LowLevelCacheApi
import earth.worldwind.layer.source.TileSource
import earth.worldwind.layer.cache.TileStore
import earth.worldwind.layer.cache.WebServiceInfo
import kotlin.time.Instant

/**
 * Persistent cache for vector-data and tile-pyramid layers. One [ContentManager] instance
 * manages many independent content entries (each keyed by `contentKey`), each backed by
 * one GeoPackage table or table set.
 *
 * The manager is a substitute for the originating network source — it stores what the
 * server returned (bytes or feature rows) and nothing more. Styling, layer-state, and
 * sprite atlases live on the caller's side and never enter the cache.
 *
 * Typical caller flow:
 *   1. `openXxxStore(contentKey, …)` to obtain a store bound to a GPKG table.
 *   2. Build a [earth.worldwind.layer.cache.CachedTileSource] /
 *      [earth.worldwind.layer.cache.CachedBulkFeatureSource] /
 *      [earth.worldwind.layer.cache.CachedTiledFeatureSource] over the store + the
 *      network source.
 *   3. Construct a layer over that cached source with the desired styling.
 *
 * For elevation coverages, prefer [createElevationSourceFactory] over `openCoverageStore`
 * directly — the factory owns the source-format → storage-format transcoding (Float32 ↔
 * Int16) so callers don't have to know how their network output format relates to the
 * disk storage layout. The returned factory plugs directly into
 * [earth.worldwind.globe.elevation.coverage.TiledElevationCoverage].
 *
 * To recreate the layer on a subsequent launch *without* contacting the server, store
 * web-service metadata via [registerWebService] and read it back via [findEntry] /
 * [listEntries] — capabilities XML/JSON in [WebServiceInfo.metadata] survives across
 * launches.
 */
interface ContentManager {
    /** Filesystem path / database file of the underlying cache. */
    val pathName: String

    /** When true, every write throws. */
    val isReadOnly: Boolean

    /** True after [close] has been called. Subsequent suspend calls on a closed manager
     *  throw — the underlying connection is gone. */
    val isClosed: Boolean get() = false

    /** Release the underlying connection (SQLite handle on JVM, IDB on JS, no-op on iOS).
     *  Idempotent. After [close] returns, [isClosed] is `true` and every other suspend
     *  method on this manager throws. */
    suspend fun close() {}

    /** Total on-disk size of the cache database. */
    suspend fun contentSize(): Long

    /** Approximate on-disk byte size of one content entry. Sums tile blobs / feature rows
     *  in the store(s) backing [contentKey]; `0` if no such content is registered. */
    suspend fun sizeBytesOf(contentKey: String): Long

    /** Most-recent modification across all content. `null` if the cache is empty. */
    suspend fun lastModifiedDate(): Instant?

    /**
     * Open (or create) a [TileStore] for image-tile data — backed by `data_type='tiles'`
     * in the GPKG schema. Used by WMS/WMTS/WebMercator and any other raster layer that
     * fetches PNG/JPEG/WebP bytes. Re-opening the same [contentKey] returns a store
     * compatible with the previously persisted [LevelSet] (validates compatibility).
     *
     * [displayName] is stored as `gpkg_contents.identifier` on **first creation** so the
     * layer's friendly name persists across launches; subsequent opens preserve the
     * already-stored identifier.
     */
    @LowLevelCacheApi
    suspend fun openImageTileStore(
        contentKey: String,
        levelSet: LevelSet,
        imageFormat: String = "image/png",
        isTransparent: Boolean = false,
        evictionPolicy: CacheEvictionPolicy = CacheEvictionPolicy.UNBOUNDED,
        displayName: String? = null,
    ): TileStore

    /**
     * Open (or create) a [TileStore] for vector-tile data — backed by
     * `data_type='vector-tiles'` (`im_vector_tiles_mapbox` extension). Used by MVT layers
     * that store raw protobuf bytes per tile.
     *
     * See [openImageTileStore] for [displayName] semantics.
     */
    @LowLevelCacheApi
    suspend fun openVectorTileStore(
        contentKey: String,
        levelSet: LevelSet,
        evictionPolicy: CacheEvictionPolicy = CacheEvictionPolicy.UNBOUNDED,
        displayName: String? = null,
    ): TileStore

    /**
     * Build an [ElevationSourceFactory] backed by this content manager's elevation cache.
     * The factory owns the source-format → storage-format transcoding for every tile —
     * callers just supply the network source (or `null` for offline replay) and the
     * desired storage layout via [isFloat].
     *
     * **This is the only public way to access elevation cache content.** The cache holds
     * encoded tile blobs (PNG-Int16 with per-tile `(scale, offset)` or TIFF-Float32) —
     * not raw network bytes — so a generic byte-level `openCoverageStore` analog of the
     * other `openXxxStore` methods would be a footgun. Pair it with `CachedTileSource`
     * and the decoder gets raw-bytes-through-BIL16-decoder garbage. This factory hides
     * that asymmetry behind a single correct entry point.
     *
     * **Transcoding semantics (platform-dependent):**
     *   - GeoPackage backend (JVM/Android): stored as TIFF-Float32 when [isFloat] is true,
     *     PNG-Int16 with per-tile `(scale, offset)` packing when [isFloat] is false. The
     *     Float→Int packing path **never** rounds through a WorldWind `ShortArray`
     *     intermediate — the in-memory `FloatBuffer` is mapped directly to 16-bit codes
     *     via per-tile linear scaling, preserving the source's precision up to the Int16
     *     quantization step. Ancillary metadata is written to
     *     `gpkg_2d_gridded_tile_ancillary` (per-tile) and
     *     `gpkg_2d_gridded_coverage_ancillary` (per-coverage).
     *   - IndexedDB backend (JS): same encode/decode pipeline, ancillary metadata stored
     *     in a sidecar `coverage_ancillary` object store keyed by
     *     `[contentKey, z, x, y]`.
     *   - Filesystem backend (iOS): same encode/decode pipeline, ancillary metadata
     *     stored in a `.ancillary` sidecar file next to each tile blob.
     *
     * [isFloat] is the storage layout. On first creation it's the chosen layout — written
     * to the platform's `dataType` attribute and locked in. On reopen the stored value is
     * canonical; a caller value that disagrees is logged (WARN) and the stored value
     * still wins. Query [findEntry]'s `metadata` (cast to
     * [CacheEntry.isFloat]) for the stored
     * answer.
     *
     * [networkSource] is the upstream tile producer (typically a WMS / WCS / WMTS source
     * adapter). Pass `null` for offline replay — cache misses then return null instead of
     * triggering a network fetch.
     *
     * [outputFormat] is the upstream's wire format (`application/bil16`, `image/tiff`,
     * etc.) — used to decode bytes returned by [networkSource]. Independent of the
     * storage format chosen via [isFloat].
     */
    @LowLevelCacheApi
    suspend fun createElevationSourceFactory(
        contentKey: String,
        tileMatrixSet: TileMatrixSet,
        networkSource: TileSource?,
        outputFormat: String,
        isFloat: Boolean = false,
        evictionPolicy: CacheEvictionPolicy = CacheEvictionPolicy.UNBOUNDED,
        displayName: String? = null,
    ): ElevationSourceFactory

    /**
     * Open (or create) a [FeatureStore] for vector-feature data — backed by
     * `data_type='features'` in the GPKG schema. Used by WFS, Shapefile (bulk rows) and
     * OsmBuildings (tile-addressed rows) in the same store shape.
     *
     * See [openImageTileStore] for [displayName] semantics.
     */
    @LowLevelCacheApi
    suspend fun openFeatureStore(
        contentKey: String,
        evictionPolicy: CacheEvictionPolicy = CacheEvictionPolicy.UNBOUNDED,
        displayName: String? = null,
    ): FeatureStore

    /**
     * Persist web-service metadata for [contentKey] in the GPKG `gpkg_web_service`
     * extension table. Capabilities XML/JSON in [WebServiceInfo.metadata] survives across
     * launches so the layer can be reconstructed without an online capabilities request.
     *
     * **Infallible contract.** Implementations catch and log any storage failures
     * (read-only DB, IDB write rejected, filesystem error, etc.) — they never throw.
     * The layer's in-memory wiring still works even if persistence fails; the user just
     * loses the ability to reopen the layer offline.
     */
    @LowLevelCacheApi
    suspend fun registerWebService(contentKey: String, info: WebServiceInfo)

    /** Enumerate every cached content entry. Lightweight — does not construct layers or
     *  read row data, only metadata. */
    suspend fun listEntries(): List<CacheEntry>

    /**
     * Look up a single content entry by [contentKey], or `null` if absent. Lightweight —
     * same shape as one element of [listEntries].
     */
    suspend fun findEntry(contentKey: String): CacheEntry?

    /**
     * Truncate one content entry's tile / feature payload while keeping its registration
     * intact — the [WebServiceInfo] row, the persisted level-set / tile-matrix-set, the
     * display name, the `isFloat` choice, and any other per-content metadata stay so the
     * caller can re-download tiles without re-configuring the layer.
     *
     * This is what a "Clear Cache" UI action wants — re-fetch the bytes, keep the layer
     * registered. Use [deleteEntry] for "remove this layer entirely" instead.
     *
     * No-op when [contentKey] doesn't exist or the manager is read-only (in the read-only
     * case, [registerWebService]-style failures are logged, not thrown).
     */
    suspend fun clearEntry(contentKey: String)

    /** Delete one content entry — drops the table(s), the web-service row, and the
     *  contents-table row. Use [clearEntry] instead when the caller wants to keep the
     *  layer registered for a re-download. */
    suspend fun deleteEntry(contentKey: String)

    /** Overwrite the user-visible display name for [contentKey]. No-op when the content
     *  key doesn't exist or the manager is read-only. */
    suspend fun setDisplayName(contentKey: String, displayName: String)

    /**
     * Recover the pyramid [LevelSet] for an image / vector-tile cache, or `null` when
     * the backend can't reconstruct one (e.g. JS / iOS where the schema doesn't persist
     * the level-set descriptors). Used by the cross-platform reopen dispatcher to
     * rebuild a layer's level-set after restart; callers fall back to a sensible default
     * (slippy-map 0..21) when this returns `null`.
     *
     * GeoPackage-backed managers override this and read `gpkg_tile_matrix_set` /
     * `gpkg_tile_matrix`. Other backends keep the default no-op until they grow their
     * own per-content schema.
     */
    @LowLevelCacheApi
    suspend fun tryRecoverLevelSet(contentKey: String): LevelSet? = null

    /**
     * Recover the [TileMatrixSet] for a coverage cache, or `null` when the backend can't
     * reconstruct one. Mirrors [tryRecoverLevelSet] for elevation coverages.
     *
     * GeoPackage-backed managers override this and read the persisted tile-matrix-set
     * shape. Other backends keep the default no-op.
     */
    @LowLevelCacheApi
    suspend fun tryRecoverTileMatrixSet(contentKey: String): TileMatrixSet? = null

    /**
     * Open a cached layer/coverage for a [CacheEntry] that has *no* registered
     * web service — i.e. the content was imported directly into the cache (e.g. a
     * standalone .gpkg file with no `gpkg_web_service` row). Returns `null` when the
     * backend can't construct a layer in this mode.
     *
     * GeoPackage-backed managers override this to provide their gpkg-native opener
     * (per-data-type layer reconstruction from `gpkg_contents` rows). Other backends
     * keep the default no-op.
     */
    suspend fun tryOpenNativeContent(entry: CacheEntry): Any? = null
}
