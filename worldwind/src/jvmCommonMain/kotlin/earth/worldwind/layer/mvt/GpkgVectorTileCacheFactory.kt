package earth.worldwind.layer.mvt

import earth.worldwind.layer.VectorTileBlob
import earth.worldwind.layer.VectorTileCacheSourceFactory
import earth.worldwind.layer.cache.CacheEvictionPolicy
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GpkgContent
import kotlin.time.Instant

/**
 * GPKG-backed [VectorTileCacheSourceFactory]. Tiles live in the standard Image Matters
 * Vector Tiles community extension layout — a normal GeoPackage tile pyramid table where
 * `tile_data` is the MVT protobuf BLOB, plus a `gpkg_extensions` row tagging the encoding
 * as `im_vector_tiles_mapbox`. External readers (QGIS, ogr2ogr, NGA geopackage-java) can
 * round-trip the caches we write.
 *
 * HTTP `ETag` / `Last-Modified` headers persist in a worldwind-private side table
 * (`gpkg_tile_revalidation`) so the layer's fetch loop can send conditional requests and
 * the server can short-circuit with 304.
 *
 * Construct via `GpkgContentManager.setupVectorTileLayerCache(...)`.
 */
class GpkgVectorTileCacheFactory(
    val geoPackage: GeoPackage,
    val content: GpkgContent,
) : VectorTileCacheSourceFactory {
    override val contentType = "GPKG"
    override val contentKey: String get() = content.tableName
    override val contentPath: String get() = geoPackage.pathName

    override suspend fun lastModifiedDate(): Instant? =
        content.lastChange?.let { Instant.fromEpochMilliseconds(it.time) }

    override suspend fun contentSize(): Long = geoPackage.readTilesDataSize(content.tableName)

    override var evictionPolicy: CacheEvictionPolicy = CacheEvictionPolicy.UNBOUNDED

    override suspend fun evict() {
        if (evictionPolicy.isUnbounded || geoPackage.isReadOnly) return
        geoPackage.evictTiles(content, evictionPolicy)
        // Orphaned revalidation rows are tiny (a few bytes each) and harmless — they're
        // overwritten on the next conditional fetch. Skip the cross-table sweep.
    }

    override suspend fun clearContent(deleteMetadata: Boolean) {
        geoPackage.clearTileRevalidation(content)
        if (deleteMetadata) geoPackage.deleteContent(content.tableName)
        else geoPackage.clearContent(content.tableName)
    }

    override suspend fun readTileBlob(z: Int, x: Int, y: Int): VectorTileBlob? {
        val (gpkgRow, gpkgCol) = toGpkgRowCol(z, x, y)
        val ud = geoPackage.readTileUserData(content, z, gpkgCol, gpkgRow) ?: return null
        val reval = geoPackage.readTileRevalidation(content, z, gpkgCol, gpkgRow)
        // tile_data is BLOB NOT NULL per the OGC tile pyramid schema. An empty byte array
        // is our "fetched-empty" sentinel that survives the NOT NULL constraint.
        return VectorTileBlob(
            bytes = ud.tileData,
            etag = reval?.etag,
            lastModified = reval?.httpLastModified,
        )
    }

    override suspend fun writeTileBlob(
        z: Int, x: Int, y: Int,
        bytes: ByteArray, etag: String?, lastModified: String?,
    ) {
        val (gpkgRow, gpkgCol) = toGpkgRowCol(z, x, y)
        geoPackage.writeTileUserData(content, z, gpkgCol, gpkgRow, bytes)
        if (etag != null || lastModified != null) {
            geoPackage.writeTileRevalidation(content, z, gpkgCol, gpkgRow, etag, lastModified)
        }
    }

    /**
     * Slippy-y → GeoPackage `tile_row` translation. The OGC tile pyramid uses the same
     * top-down convention as slippy maps (row 0 at the north edge), so this is a no-op
     * for now — kept as a hook so we can flip if a future spec revision diverges.
     */
    private fun toGpkgRowCol(z: Int, x: Int, y: Int): Pair<Int, Int> = y to x
}
