package earth.worldwind.ogc.gpkg

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import java.io.Serializable

/**
 * CREATE TABLE gpkg_tile_matrix (
 *   table_name TEXT NOT NULL,
 *   zoom_level INTEGER NOT NULL,
 *   matrix_width INTEGER NOT NULL,
 *   matrix_height INTEGER NOT NULL,
 *   tile_width INTEGER NOT NULL,
 *   tile_height INTEGER NOT NULL,
 *   pixel_x_size DOUBLE NOT NULL,
 *   pixel_y_size DOUBLE NOT NULL,
 *   CONSTRAINT pk_ttm PRIMARY KEY (table_name, zoom_level),
 *   CONSTRAINT fk_tmm_table_name FOREIGN KEY (table_name) REFERENCES gpkg_contents(table_name)
 * );
 */
@DatabaseTable(tableName = "gpkg_tile_matrix")
class GpkgTileMatrix : Serializable {
    @DatabaseField(columnName = CONTENT, dataType = DataType.SERIALIZABLE, canBeNull = false, uniqueCombo = true, foreign = true)
    lateinit var content: GpkgContent
    @DatabaseField(columnName = ZOOM_LEVEL, dataType = DataType.INTEGER, canBeNull = false, uniqueCombo = true)
    var zoomLevel: Int = 0
    @DatabaseField(columnName = MATRIX_WIDTH, dataType = DataType.INTEGER, canBeNull = false)
    var matrixWidth: Int = 0
    @DatabaseField(columnName = MATRIX_HEIGHT, dataType = DataType.INTEGER, canBeNull = false)
    var matrixHeight: Int = 0
    @DatabaseField(columnName = TILE_WIDTH, dataType = DataType.INTEGER, canBeNull = false)
    var tileWidth: Int = 0
    @DatabaseField(columnName = TILE_HEIGHT, dataType = DataType.INTEGER, canBeNull = false)
    var tileHeight: Int = 0
    @DatabaseField(columnName = PIXEL_X_SIZE, dataType = DataType.DOUBLE, canBeNull = false)
    var pixelXSize: Double = 0.0
    @DatabaseField(columnName = PIXEL_Y_SIZE, dataType = DataType.DOUBLE, canBeNull = false)
    var pixelYSize: Double = 0.0

    companion object {
        const val CONTENT = "table_name"
        const val ZOOM_LEVEL = "zoom_level"
        const val MATRIX_WIDTH = "matrix_width"
        const val MATRIX_HEIGHT = "matrix_height"
        const val TILE_WIDTH = "tile_width"
        const val TILE_HEIGHT = "tile_height"
        const val PIXEL_X_SIZE = "pixel_x_size"
        const val PIXEL_Y_SIZE = "pixel_y_size"
    }
}