package earth.worldwind.layer.atak

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import earth.worldwind.layer.atak.ATAKMetadata.Companion.TABLE_NAME

@DatabaseTable(tableName = TABLE_NAME)
class ATAKMetadata {
    @DatabaseField(columnName = KEY, dataType = DataType.STRING, id = true)
    var key: String? = null
    @DatabaseField(columnName = VALUE, dataType = DataType.STRING)
    var value: String? = null

    companion object {
        const val TABLE_NAME = "ATAK_metadata"
        const val KEY = "key"
        const val VALUE = "value"
    }
}