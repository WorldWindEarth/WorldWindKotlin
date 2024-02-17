package earth.worldwind.ogc.gpkg

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import java.io.Serializable

/**
 * CREATE TABLE gpkg_tile_matrix_set (
 *   table_name TEXT NOT NULL PRIMARY KEY,
 *   srs_id INTEGER NOT NULL,
 *   min_x DOUBLE NOT NULL,
 *   min_y DOUBLE NOT NULL,
 *   max_x DOUBLE NOT NULL,
 *   max_y DOUBLE NOT NULL,
 *   CONSTRAINT fk_gtms_table_name FOREIGN KEY (table_name) REFERENCES gpkg_contents(table_name),
 *   CONSTRAINT fk_gtms_srs FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys (srs_id)
 * );
 */
@DatabaseTable(tableName = "gpkg_tile_matrix_set")
class GpkgTileMatrixSet : Serializable {
    @DatabaseField(columnName = TABLE_NAME, dataType = DataType.STRING, canBeNull = false, id = true)
    lateinit var tableName: String
    @DatabaseField(columnName = SRS, dataType = DataType.SERIALIZABLE, canBeNull = false, foreign = true)
    lateinit var srs: GpkgSpatialReferenceSystem
    @DatabaseField(columnName = MIN_X, dataType = DataType.DOUBLE, canBeNull = false)
    var minX: Double = 0.0
    @DatabaseField(columnName = MIN_Y, dataType = DataType.DOUBLE, canBeNull = false)
    var minY: Double = 0.0
    @DatabaseField(columnName = MAX_X, dataType = DataType.DOUBLE, canBeNull = false)
    var maxX: Double = 0.0
    @DatabaseField(columnName = MAX_Y, dataType = DataType.DOUBLE, canBeNull = false)
    var maxY: Double = 0.0

    companion object {
        const val TABLE_NAME = "table_name"
        const val SRS = "srs_id"
        const val MIN_X = "min_x"
        const val MIN_Y = "min_y"
        const val MAX_X = "max_x"
        const val MAX_Y = "max_y"
    }
}