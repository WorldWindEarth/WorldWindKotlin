package earth.worldwind.formats.gpkg

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import java.io.Serializable

/**
 * Worldwind-private side table tracking the feature *cache* state for one features table, kept
 * separate from the features themselves so the features table can be a clean, standard GeoPackage
 * `features` layer that any GIS (ArcGIS / QGIS / GDAL) reads natively.
 *
 * This is the feature analogue of [GpkgTileRevalidation], but a feature "tile" is *many* rows
 * (one per geometry) rather than a single tile-user-data row, so coverage is keyed by the slippy
 * tile address `(tpudt_name, tile_z, tile_x, tile_y)` rather than a single row id. One row records,
 * per fetched tile region:
 *   * [validatedAt] — epoch-millis the region was last confirmed fresh (stale-while-revalidate
 *     trigger and eviction age), bumped on a fresh fetch AND on a 304.
 *   * [etag] / [httpLastModified] — HTTP validators for a conditional refresh.
 *   * [isEmpty] — the negative cache: the region was fetched and the server returned no features.
 *     Moving this here removes the null-geometry sentinel rows that used to pollute the features
 *     table and confuse external readers.
 *
 * Not part of the OGC GeoPackage standard — external readers see an unknown table and ignore it.
 * The `ww_` author prefix stays off the spec-reserved `gpkg_` namespace; the table is declared in
 * gpkg_extensions (worldwind_feature_coverage), scoped to itself, so foreign GIS still edit the
 * standard features layer freely.
 *
 * ```
 * CREATE TABLE IF NOT EXISTS ww_feature_coverage (
 *   id INTEGER PRIMARY KEY AUTOINCREMENT,
 *   tpudt_name TEXT NOT NULL,    -- the features table this coverage row describes
 *   tile_z     INTEGER NOT NULL,
 *   tile_x     INTEGER NOT NULL,
 *   tile_y     INTEGER NOT NULL,
 *   etag       TEXT,
 *   http_last_modified TEXT,
 *   validated_at INTEGER,
 *   is_empty   INTEGER NOT NULL DEFAULT 0,
 *   UNIQUE (tpudt_name, tile_z, tile_x, tile_y)
 * )
 * ```
 */
@DatabaseTable(tableName = GpkgFeatureCoverage.TABLE_NAME)
class GpkgFeatureCoverage : Serializable {
    @DatabaseField(columnName = COLUMN_ID, dataType = DataType.LONG, generatedId = true)
    var id: Long = 0L

    @DatabaseField(columnName = COLUMN_TPUDT_NAME, dataType = DataType.STRING, canBeNull = false, uniqueCombo = true)
    lateinit var tpudtName: String

    @DatabaseField(columnName = COLUMN_TILE_Z, dataType = DataType.INTEGER, canBeNull = false, uniqueCombo = true)
    var tileZ: Int = 0

    @DatabaseField(columnName = COLUMN_TILE_X, dataType = DataType.INTEGER, canBeNull = false, uniqueCombo = true)
    var tileX: Int = 0

    @DatabaseField(columnName = COLUMN_TILE_Y, dataType = DataType.INTEGER, canBeNull = false, uniqueCombo = true)
    var tileY: Int = 0

    @DatabaseField(columnName = COLUMN_ETAG, dataType = DataType.STRING)
    var etag: String? = null

    @DatabaseField(columnName = COLUMN_HTTP_LAST_MODIFIED, dataType = DataType.STRING)
    var httpLastModified: String? = null

    @DatabaseField(columnName = COLUMN_VALIDATED_AT, dataType = DataType.LONG_OBJ)
    var validatedAt: Long? = null

    @DatabaseField(columnName = COLUMN_IS_EMPTY, dataType = DataType.BOOLEAN, canBeNull = false)
    var isEmpty: Boolean = false

    companion object {
        const val TABLE_NAME = "ww_feature_coverage"
        const val COLUMN_ID = "id"
        const val COLUMN_TPUDT_NAME = "tpudt_name"
        const val COLUMN_TILE_Z = "tile_z"
        const val COLUMN_TILE_X = "tile_x"
        const val COLUMN_TILE_Y = "tile_y"
        const val COLUMN_ETAG = "etag"
        const val COLUMN_HTTP_LAST_MODIFIED = "http_last_modified"
        const val COLUMN_VALIDATED_AT = "validated_at"
        const val COLUMN_IS_EMPTY = "is_empty"
    }
}
