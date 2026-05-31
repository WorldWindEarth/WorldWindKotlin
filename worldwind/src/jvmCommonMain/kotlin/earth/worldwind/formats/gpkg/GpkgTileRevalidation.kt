package earth.worldwind.formats.gpkg

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import java.io.Serializable

/**
 * Worldwind-private side table that stores HTTP `ETag` and `Last-Modified` headers per
 * cached tile, so cache reads can issue conditional requests (`If-None-Match` /
 * `If-Modified-Since`) and the server can answer 304 to skip the body.
 *
 * Not part of the OGC GeoPackage standard — external readers see an unknown table and
 * ignore it. The standard tile-user-data table stays clean.
 *
 * Composite key encoded into [id] as `"<tableName>|<z>|<x>|<y>"` so ORMLite can use a
 * single string PK; the broken-out columns are kept for diagnostic queries / bulk delete.
 *
 * ```
 * CREATE TABLE IF NOT EXISTS gpkg_tile_revalidation (
 *   id TEXT NOT NULL PRIMARY KEY,
 *   table_name  TEXT NOT NULL,
 *   zoom_level  INTEGER NOT NULL,
 *   tile_column INTEGER NOT NULL,
 *   tile_row    INTEGER NOT NULL,
 *   etag        TEXT,
 *   http_last_modified TEXT
 * )
 * ```
 */
@DatabaseTable(tableName = GpkgTileRevalidation.TABLE_NAME)
class GpkgTileRevalidation : Serializable {
    @DatabaseField(columnName = COLUMN_ID, dataType = DataType.STRING, canBeNull = false, id = true)
    lateinit var id: String

    @DatabaseField(columnName = COLUMN_TABLE_NAME, dataType = DataType.STRING, canBeNull = false, index = true)
    lateinit var tableName: String

    @DatabaseField(columnName = COLUMN_ZOOM_LEVEL, dataType = DataType.INTEGER, canBeNull = false)
    var zoomLevel: Int = 0

    @DatabaseField(columnName = COLUMN_TILE_COLUMN, dataType = DataType.INTEGER, canBeNull = false)
    var tileColumn: Int = 0

    @DatabaseField(columnName = COLUMN_TILE_ROW, dataType = DataType.INTEGER, canBeNull = false)
    var tileRow: Int = 0

    @DatabaseField(columnName = COLUMN_ETAG, dataType = DataType.STRING)
    var etag: String? = null

    @DatabaseField(columnName = COLUMN_HTTP_LAST_MODIFIED, dataType = DataType.STRING)
    var httpLastModified: String? = null

    companion object {
        const val TABLE_NAME = "gpkg_tile_revalidation"
        const val COLUMN_ID = "id"
        const val COLUMN_TABLE_NAME = "table_name"
        const val COLUMN_ZOOM_LEVEL = "zoom_level"
        const val COLUMN_TILE_COLUMN = "tile_column"
        const val COLUMN_TILE_ROW = "tile_row"
        const val COLUMN_ETAG = "etag"
        const val COLUMN_HTTP_LAST_MODIFIED = "http_last_modified"

        fun keyOf(tableName: String, z: Int, x: Int, y: Int) = "$tableName|$z|$x|$y"
    }
}
