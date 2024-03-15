package earth.worldwind.layer.mbtiles

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import earth.worldwind.layer.mbtiles.MBTiles.Companion.TABLE_NAME

@DatabaseTable(tableName = TABLE_NAME)
class MBTiles {
    @DatabaseField(columnName = ZOOM_LEVEL, dataType = DataType.INTEGER, uniqueCombo = true)
    var zoomLevel = 0
    @DatabaseField(columnName = TILE_COLUMN, dataType = DataType.INTEGER, uniqueCombo = true)
    var tileColumn = 0
    @DatabaseField(columnName = TILE_ROW, dataType = DataType.INTEGER, uniqueCombo = true)
    var tileRow = 0
    @DatabaseField(columnName = TILE_DATA, dataType = DataType.BYTE_ARRAY)
    var tileData: ByteArray? = null

    companion object {
        const val TABLE_NAME = "tiles"
        const val ZOOM_LEVEL = "zoom_level"
        const val TILE_COLUMN = "tile_column"
        const val TILE_ROW = "tile_row"
        const val TILE_DATA = "tile_data"
    }
}