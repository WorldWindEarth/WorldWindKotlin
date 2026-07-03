package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileBlob

import earth.worldwind.formats.gpkg.GeoPackage
import earth.worldwind.formats.gpkg.GpkgContent

/**
 * GeoPackage-backed [TileStore]. One instance binds to one tile-pyramid table — works for
 * any `data_type` that uses the tile-user-data shape (`image/png`, `image/jpeg`,
 * `image/webp`, gridded coverages, vector-tile protobuf).
 *
 * `(z, x, y)` maps to `(zoom_level, tile_column, tile_row)`. ETag / Last-Modified ride
 * alongside in the `ww_tile_revalidation` side table, so a conditional GET against
 * the network source on a follow-up refresh stays cheap.
 *
 * Tile-payload format is whatever the source produced — the store doesn't decode. Image
 * layers decode PNG/JPEG/WebP at render time; elevation layers decode the gridded blob;
 * MVT layers parse the protobuf.
 */
class GpkgTileStore(
    private val geoPackage: GeoPackage,
    private val content: GpkgContent,
    override val cachePolicy: CachePolicy = CachePolicy.UNBOUNDED,
) : TileStore, CachedSourceInfoProvider {

    override val cacheInfo: CachedSourceInfo
        get() = CachedSourceInfo(contentKey = content.tableName, contentPath = geoPackage.pathName)

    override suspend fun readTile(z: Int, x: Int, y: Int): TileBlob? {
        val row = geoPackage.readTileUserData(content, z, x, y) ?: return null
        if (row.tileData.isEmpty()) return TileBlob.EMPTY
        // Freshness tracking on (finite staleAfter): pull the ww_tile_revalidation row (keyed by this
        // tile row's id) so the SWR refresh can issue a conditional GET (ETag / Last-Modified) and
        // so `validatedAt` drives staleness. The tile row is already loaded, so its id is free;
        // skipped entirely when staleAfter is INFINITE.
        val tracked = cachePolicy.tracksFreshness
        val reval = if (tracked) geoPackage.readTileRevalidation(content, row.id) else null
        return TileBlob(
            bytes = row.tileData,
            etag = reval?.etag,
            lastModified = reval?.httpLastModified,
            // Self-heal: a tile cached before this row existed (pre-rename build, or freshness was
            // off when it was written) has no validatedAt. Treat it as stale (epoch 0) so the first
            // tracked read kicks one revalidation pass that stamps validatedAt — rather than null,
            // which would freeze it as never-refreshable. INFINITE staleAfter → null = no SWR at all.
            cachedAt = if (tracked) (reval?.validatedAt ?: 0L) else null,
        )
    }

    override suspend fun writeTile(z: Int, x: Int, y: Int, blob: TileBlob) {
        // Empty bytes act as the "no tile at this address" sentinel (HTTP 404 etc.) — store
        // a zero-length array so the next lookup short-circuits without a network call.
        val tpudtId = geoPackage.writeTileUserData(content, z, x, y, blob.bytes)
        // Stamp freshness against the tile row's id: validators (if the server sent any) plus
        // validatedAt = now, so this tile won't be re-requested until it ages past staleAfter again.
        geoPackage.writeTileRevalidation(
            content, tpudtId, blob.etag, blob.lastModified, System.currentTimeMillis(),
        )
        // Per-put eviction trigger — async drain fires when the table crosses the overshoot budget.
        geoPackage.notifyTileInsert(content, cachePolicy)
    }

    override suspend fun bumpValidatedAt(z: Int, x: Int, y: Int) {
        if (geoPackage.isReadOnly) return
        val tpudtId = geoPackage.readTileUserDataId(content, z, x, y) ?: return
        geoPackage.bumpTileValidatedAt(content, tpudtId, System.currentTimeMillis())
    }

    override suspend fun deleteTile(z: Int, x: Int, y: Int) {
        // Writing an empty blob is the closest we have to "drop" — preserves the negative-
        // cache marker so the layer doesn't re-query immediately. A true row delete would
        // require a per-tile DAO path; leave that for the explicit clearEntry() route.
        geoPackage.writeTileUserData(content, z, x, y, ByteArray(0))
    }

    override suspend fun evict() {
        if (cachePolicy.isUnbounded || geoPackage.isReadOnly) return
        geoPackage.evictTiles(content, cachePolicy)
    }

    override suspend fun sizeBytes(): Long = geoPackage.readTilesDataSize(content.tableName)
}
