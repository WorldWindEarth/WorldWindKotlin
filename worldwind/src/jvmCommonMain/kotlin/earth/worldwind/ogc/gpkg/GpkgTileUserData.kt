package earth.worldwind.ogc.gpkg

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import java.io.Serializable

/**
 * CREATE TABLE sample_tile_pyramid (
 *   id INTEGER PRIMARY KEY AUTOINCREMENT,
 *   zoom_level INTEGER NOT NULL,
 *   tile_column INTEGER NOT NULL,
 *   tile_row INTEGER NOT NULL,
 *   tile_data BLOB NOT NULL,
 *   UNIQUE (zoom_level, tile_column, tile_row)
 * );
 */
@DatabaseTable(tableName = "sample_tile_pyramid")
class GpkgTileUserData : Serializable {
    @DatabaseField(columnName = ID, dataType = DataType.LONG, generatedId = true)
    var id: Long = 0L
    @DatabaseField(columnName = ZOOM_LEVEL, dataType = DataType.INTEGER, canBeNull = false, uniqueCombo = true)
    var zoomLevel: Int = 0
    @DatabaseField(columnName = TILE_COLUMN, dataType = DataType.INTEGER, canBeNull = false, uniqueCombo = true)
    var tileColumn: Int = 0
    @DatabaseField(columnName = TILE_ROW, dataType = DataType.INTEGER, canBeNull = false, uniqueCombo = true)
    var tileRow: Int = 0
    @DatabaseField(columnName = TILE_DATA, dataType = DataType.BYTE_ARRAY, canBeNull = false)
    lateinit var tileData: ByteArray

    companion object {
        const val ID = "id"
        const val ZOOM_LEVEL = "zoom_level"
        const val TILE_COLUMN = "tile_column"
        const val TILE_ROW = "tile_row"
        const val TILE_DATA = "tile_data"
    }
}