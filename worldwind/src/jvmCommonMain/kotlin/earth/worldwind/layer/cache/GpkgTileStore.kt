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
 * alongside in the `gpkg_tile_revalidation` extension table, so a conditional GET against
 * the network source on a follow-up refresh stays cheap.
 *
 * Tile-payload format is whatever the source produced — the store doesn't decode. Image
 * layers decode PNG/JPEG/WebP at render time; elevation layers decode the gridded blob;
 * MVT layers parse the protobuf.
 */
class GpkgTileStore(
    private val geoPackage: GeoPackage,
    private val content: GpkgContent,
    override val evictionPolicy: CacheEvictionPolicy = CacheEvictionPolicy.UNBOUNDED,
) : TileStore, CachedSourceInfoProvider {

    override val cacheInfo: CachedSourceInfo
        get() = CachedSourceInfo(contentKey = content.tableName, contentPath = geoPackage.pathName)

    override suspend fun readTile(z: Int, x: Int, y: Int): TileBlob? {
        val row = geoPackage.readTileUserData(content, z, x, y) ?: return null
        if (row.tileData.isEmpty()) return TileBlob.EMPTY
        // Serve the cached bytes directly. ETag / Last-Modified are deliberately NOT read
        // here: nothing on the render path issues a conditional GET (cache hits are served
        // verbatim), so joining gpkg_tile_revalidation on every tile read — 100+ tiles per
        // frame during a pan — is pure overhead. A revalidation-on-refresh path can read it
        // on demand via geoPackage.readTileRevalidation.
        return TileBlob(bytes = row.tileData)
    }

    override suspend fun writeTile(z: Int, x: Int, y: Int, blob: TileBlob) {
        // Empty bytes act as the "no tile at this address" sentinel (HTTP 404 etc.) — store
        // a zero-length array so the next lookup short-circuits without a network call.
        geoPackage.writeTileUserData(content, z, x, y, blob.bytes)
        // writeTileRevalidation is a no-op when both headers are null, so the stale row
        // from a previous fetch survives here. The cost is a stale 304-revalidation hint
        // until the next write supplies headers, which the network source handles cleanly.
        geoPackage.writeTileRevalidation(content, z, x, y, blob.etag, blob.lastModified)
    }

    override suspend fun deleteTile(z: Int, x: Int, y: Int) {
        // Writing an empty blob is the closest we have to "drop" — preserves the negative-
        // cache marker so the layer doesn't re-query immediately. A true row delete would
        // require a per-tile DAO path; leave that for the explicit clearEntry() route.
        geoPackage.writeTileUserData(content, z, x, y, ByteArray(0))
    }

    override suspend fun evict() {
        if (evictionPolicy.isUnbounded || geoPackage.isReadOnly) return
        geoPackage.evictTiles(content, evictionPolicy)
    }

    override suspend fun sizeBytes(): Long = geoPackage.readTilesDataSize(content.tableName)
}
