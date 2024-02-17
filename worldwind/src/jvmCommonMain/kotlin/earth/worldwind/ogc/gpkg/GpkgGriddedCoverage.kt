package earth.worldwind.ogc.gpkg

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import java.io.Serializable

/**
 * CREATE TABLE 'gpkg_2d_gridded_coverage_ancillary' (
 *   id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
 *   tile_matrix_set_name TEXT NOT NULL UNIQUE,
 *   datatype TEXT NOT NULL DEFAULT 'integer',
 *   scale REAL NOT NULL DEFAULT 1.0,
 *   offset REAL NOT NULL DEFAULT 0.0,
 *   precision REAL DEFAULT 1.0,
 *   data_null REAL,
 *   grid_cell_encoding TEXT DEFAULT 'grid-value-is-center',
 *   uom TEXT,
 *   field_name TEXT DEFAULT 'Height',
 *   quantity_definition TEXT DEFAULT 'Height',
 *   CONSTRAINT fk_g2dgtct_name FOREIGN KEY('tile_matrix_set_name') REFERENCES gpkg_tile_matrix_set ( table_name ),
 *   CHECK (datatype IN ('integer','float'))
 * );
 */
@DatabaseTable(tableName = "gpkg_2d_gridded_coverage_ancillary")
class GpkgGriddedCoverage : Serializable {
    @DatabaseField(columnName = ID, dataType = DataType.INTEGER, canBeNull = false, generatedId = true)
    var id: Int = 0
    @DatabaseField(columnName = TILE_MATRIX_SET_NAME, dataType = DataType.STRING, canBeNull = false, unique = true)
    lateinit var tileMatrixSetName: String
    @DatabaseField(columnName = DATATYPE, dataType = DataType.STRING, canBeNull = false)
    var datatype: String = "integer"
    @DatabaseField(columnName = SCALE, dataType = DataType.FLOAT, canBeNull = false)
    var scale: Float = 1.0f
    @DatabaseField(columnName = OFFSET, dataType = DataType.FLOAT, canBeNull = false)
    var offset: Float = 0.0f
    @DatabaseField(columnName = PRECISION, dataType = DataType.FLOAT_OBJ)
    var precision: Float? = 1.0f
    @DatabaseField(columnName = DATA_NULL, dataType = DataType.FLOAT_OBJ)
    var dataNull: Float? = null
    @DatabaseField(columnName = GRID_CELL_ENCODING, dataType = DataType.STRING)
    var gridCellEncoding: String? = "grid-value-is-center"
    @DatabaseField(columnName = UOM, dataType = DataType.STRING)
    var uom: String? = null
    @DatabaseField(columnName = FIELD_NAME, dataType = DataType.STRING)
    var fieldName: String? = "Height"
    @DatabaseField(columnName = QUANTITY_DEFINITION, dataType = DataType.STRING)
    var quantityDefinition: String? = "Height"

    companion object {
        const val ID = "id"
        const val TILE_MATRIX_SET_NAME = "tile_matrix_set_name"
        const val DATATYPE = "datatype"
        const val SCALE = "scale"
        const val OFFSET = "offset"
        const val PRECISION = "precision"
        const val DATA_NULL = "data_null"
        const val GRID_CELL_ENCODING = "grid_cell_encoding"
        const val UOM = "uom"
        const val FIELD_NAME = "field_name"
        const val QUANTITY_DEFINITION = "quantity_definition"
    }
}