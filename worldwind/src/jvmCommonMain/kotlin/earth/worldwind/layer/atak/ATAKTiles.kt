package earth.worldwind.layer.atak

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import earth.worldwind.layer.atak.ATAKTiles.Companion.TABLE_NAME

@DatabaseTable(tableName = TABLE_NAME)
class ATAKTiles {
    @DatabaseField(columnName = KEY, dataType = DataType.INTEGER, id = true)
    var key = 0
    @DatabaseField(columnName = PROVIDER, dataType = DataType.STRING)
    var provider: String? = null
    @DatabaseField(columnName = TILE, dataType = DataType.BYTE_ARRAY)
    var tile: ByteArray? = null

    companion object {
        const val TABLE_NAME = "tiles"
        const val KEY = "key"
        const val PROVIDER = "provider"
        const val TILE = "tile"
    }
}