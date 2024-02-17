package earth.worldwind.ogc.gpkg

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import java.io.Serializable

/**
 * CREATE TABLE gpkg_2d_gridded_tile_ancillary (
 *   id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
 *   tpudt_name TEXT NOT NULL,
 *   tpudt_id INTEGER NOT NULL,
 *   scale REAL NOT NULL DEFAULT 1.0,
 *   offset REAL NOT NULL DEFAULT 0.0,
 *   min REAL DEFAULT NULL,
 *   max REAL DEFAULT NULL,
 *   mean REAL DEFAULT NULL,
 *   std_dev REAL DEFAULT NULL,
 *   CONSTRAINT fk_g2dgtat_name FOREIGN KEY (tpudt_name) REFERENCES gpkg_contents(table_name),
 *   UNIQUE (tpudt_name, tpudt_id)
 * );
 */
@DatabaseTable(tableName = "gpkg_2d_gridded_tile_ancillary")
class GpkgGriddedTile : Serializable {
    @DatabaseField(columnName = ID, dataType = DataType.INTEGER, canBeNull = false, generatedId = true)
    var id: Int = 0
    @DatabaseField(columnName = CONTENT, dataType = DataType.SERIALIZABLE, canBeNull = false, uniqueCombo = true, foreign = true)
    lateinit var content: GpkgContent
    @DatabaseField(columnName = TILE_ID, dataType = DataType.INTEGER, canBeNull = false, uniqueCombo = true)
    var tileId: Int = 0
    @DatabaseField(columnName = SCALE, dataType = DataType.FLOAT, canBeNull = false)
    var scale: Float = 1.0f
    @DatabaseField(columnName = OFFSET, dataType = DataType.FLOAT, canBeNull = false)
    var offset: Float = 0.0f
    @DatabaseField(columnName = MIN, dataType = DataType.FLOAT_OBJ)
    var min: Float? = null
    @DatabaseField(columnName = MAX, dataType = DataType.FLOAT_OBJ)
    var max: Float? = null
    @DatabaseField(columnName = MEAN, dataType = DataType.FLOAT_OBJ)
    var mean: Float? = null
    @DatabaseField(columnName = STD_DEV, dataType = DataType.FLOAT_OBJ)
    var stdDev: Float? = null

    companion object {
        const val ID = "id"
        const val CONTENT = "tpudt_name"
        const val TILE_ID = "tpudt_id"
        const val SCALE = "scale"
        const val OFFSET = "offset"
        const val MIN = "min"
        const val MAX = "max"
        const val MEAN = "mean"
        const val STD_DEV = "std_dev"
    }
}