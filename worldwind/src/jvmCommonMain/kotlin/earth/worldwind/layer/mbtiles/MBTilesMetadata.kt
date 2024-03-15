package earth.worldwind.layer.mbtiles

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import earth.worldwind.layer.mbtiles.MBTilesMetadata.Companion.TABLE_NAME

@DatabaseTable(tableName = TABLE_NAME)
class MBTilesMetadata {
    @DatabaseField(columnName = NAME, dataType = DataType.STRING, id = true)
    var name: String? = null
    @DatabaseField(columnName = VALUE, dataType = DataType.STRING)
    var value: String? = null

    companion object {
        const val TABLE_NAME = "metadata"
        const val NAME = "name"
        const val VALUE = "value"
    }
}