package earth.worldwind.formats.gpkg

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import java.io.Serializable

/**
 * Worldwind-private side table that holds per-tile cache freshness metadata: the HTTP `ETag`
 * and `Last-Modified` validators (so cache refreshes can issue conditional `If-None-Match` /
 * `If-Modified-Since` requests and the server can answer 304 to skip the body), plus
 * [validatedAt] — epoch-millis the tile was last confirmed fresh against the server.
 *
 * [validatedAt] is the stale-while-revalidate trigger: when `now - validatedAt` exceeds the
 * eviction `staleAfter`, a background refresh fires. It is bumped on a fresh download AND on a 304,
 * so a still-current tile doesn't get re-requested until the next window. It lives here, not as
 * a `last_modified` column on the OGC tile-user-data table, so the standard table stays pristine.
 *
 * Not part of the OGC GeoPackage standard — external readers see an unknown table and ignore
 * it. The `ww_` author prefix deliberately stays off the spec-reserved `gpkg_` namespace. (An
 * earlier build wrote a `gpkg_tile_revalidation` table; this rename starts a fresh schema rather
 * than migrating it — see [GeoPackage.writeTileRevalidation] — so any such legacy table is just
 * left orphaned, and freshness self-heals on first read.)
 *
 * Keyed by `(tpudt_name, tpudt_id)` — the tile-pyramid-user-data table name plus the `id` of the
 * tile's row in it — exactly like the OGC `gpkg_2d_gridded_tile_ancillary` table (and this repo's
 * `GriddedTile` usage). A compact 2-column unique combo; [id] is just an autoincrement rowid
 * alias (free in SQLite). The combo's leading `tpudt_name` column also serves the per-content bulk
 * delete in [GeoPackage.clearTileRevalidation]. Tile ids are stable across rewrites (the tile row
 * is reused), and anything missed self-heals on first read.
 *
 * ```
 * CREATE TABLE IF NOT EXISTS ww_tile_revalidation (
 *   id INTEGER PRIMARY KEY AUTOINCREMENT,
 *   tpudt_name TEXT NOT NULL,    -- tile-pyramid-user-data table name
 *   tpudt_id   INTEGER NOT NULL, -- id of the tile's row in that table
 *   etag       TEXT,
 *   http_last_modified TEXT,
 *   validated_at INTEGER,
 *   UNIQUE (tpudt_name, tpudt_id)
 * )
 * ```
 */
@DatabaseTable(tableName = GpkgTileRevalidation.TABLE_NAME)
class GpkgTileRevalidation : Serializable {
    @DatabaseField(columnName = COLUMN_ID, dataType = DataType.LONG, generatedId = true)
    var id: Long = 0L

    @DatabaseField(columnName = COLUMN_TPUDT_NAME, dataType = DataType.STRING, canBeNull = false, uniqueCombo = true)
    lateinit var tpudtName: String

    @DatabaseField(columnName = COLUMN_TPUDT_ID, dataType = DataType.LONG, canBeNull = false, uniqueCombo = true)
    var tpudtId: Long = 0L

    @DatabaseField(columnName = COLUMN_ETAG, dataType = DataType.STRING)
    var etag: String? = null

    @DatabaseField(columnName = COLUMN_HTTP_LAST_MODIFIED, dataType = DataType.STRING)
    var httpLastModified: String? = null

    @DatabaseField(columnName = COLUMN_VALIDATED_AT, dataType = DataType.LONG_OBJ)
    var validatedAt: Long? = null

    companion object {
        const val TABLE_NAME = "ww_tile_revalidation"
        const val COLUMN_ID = "id"
        const val COLUMN_TPUDT_NAME = "tpudt_name"
        const val COLUMN_TPUDT_ID = "tpudt_id"
        const val COLUMN_ETAG = "etag"
        const val COLUMN_HTTP_LAST_MODIFIED = "http_last_modified"
        const val COLUMN_VALIDATED_AT = "validated_at"
    }
}
