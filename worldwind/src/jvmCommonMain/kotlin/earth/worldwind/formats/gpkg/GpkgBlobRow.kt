package earth.worldwind.formats.gpkg

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import java.io.Serializable

/**
 * ORMLite row for one entry in a 3D Tiles GeoPackage extension blob table. Column shape
 * follows the OGC 3D Tiles GeoPackage Extension community proposal:
 *
 *  - `tile_path` TEXT PK — content URI (relative or absolute) the tile was fetched from.
 *  - `tile_data` BLOB — raw payload bytes (tileset.json text, b3dm/i3dm/pnts/cmpt/glb binary).
 *
 * WorldWind extends the proposed shape with conditional-fetch + bookkeeping columns; these
 * are tolerated by other readers since the GPKG spec lets non-required columns exist on any
 * user table:
 *
 *  - `content_type` TEXT — HTTP Content-Type at fetch time (nullable).
 *  - `etag` TEXT — HTTP ETag at fetch time (nullable; for future revalidation).
 *  - `last_modified` INTEGER — HTTP Last-Modified epoch-millis (nullable).
 *  - `cached_at` INTEGER — epoch-millis the row was written. Drives
 *    [earth.worldwind.layer.cache.CachePolicy.staleAfter].
 *  - `size_bytes` INTEGER — LENGTH(tile_data) precomputed at insert. Lets
 *    `SUM(size_bytes)` evict by byte budget without scanning each BLOB body.
 *  - `response_url` TEXT — post-redirect URL the body was served from. Null when same as
 *    `tile_path`. Lets the cache replay the right base for relative URI resolution.
 *
 * The `@DatabaseTable` annotation is intentionally absent — the table name is supplied
 * per-dataset at DAO construction so a single GeoPackage can host many independent 3D Tiles
 * blob caches.
 */
class GpkgBlobRow : Serializable {
    @DatabaseField(columnName = COLUMN_TILE_PATH, dataType = DataType.STRING, canBeNull = false, id = true)
    lateinit var tilePath: String

    @DatabaseField(columnName = COLUMN_TILE_DATA, dataType = DataType.BYTE_ARRAY, canBeNull = false)
    lateinit var tileData: ByteArray

    @DatabaseField(columnName = COLUMN_CONTENT_TYPE, dataType = DataType.STRING)
    var contentType: String? = null

    @DatabaseField(columnName = COLUMN_ETAG, dataType = DataType.STRING)
    var etag: String? = null

    @DatabaseField(columnName = COLUMN_LAST_MODIFIED, dataType = DataType.LONG_OBJ)
    var lastModified: Long? = null

    @DatabaseField(columnName = COLUMN_CACHED_AT, dataType = DataType.LONG)
    var cachedAt: Long = 0L

    @DatabaseField(columnName = COLUMN_SIZE_BYTES, dataType = DataType.LONG)
    var sizeBytes: Long = 0L

    @DatabaseField(columnName = COLUMN_RESPONSE_URL, dataType = DataType.STRING)
    var responseUrl: String? = null

    companion object {
        const val COLUMN_TILE_PATH = "tile_path"
        const val COLUMN_TILE_DATA = "tile_data"
        const val COLUMN_CONTENT_TYPE = "content_type"
        const val COLUMN_ETAG = "etag"
        const val COLUMN_LAST_MODIFIED = "last_modified"
        const val COLUMN_CACHED_AT = "cached_at"
        const val COLUMN_SIZE_BYTES = "size_bytes"
        const val COLUMN_RESPONSE_URL = "response_url"
    }
}
