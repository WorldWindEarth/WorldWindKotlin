package earth.worldwind.layer.atak

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import earth.worldwind.layer.atak.ATAKCatalog.Companion.TABLE_NAME

@DatabaseTable(tableName = TABLE_NAME)
class ATAKCatalog {
    @DatabaseField(columnName = KEY, dataType = DataType.INTEGER, id = true)
    var key = 0
    @DatabaseField(columnName = ACCESS, dataType = DataType.INTEGER_OBJ)
    var access: Int? = null
    @DatabaseField(columnName = EXPIRATION, dataType = DataType.INTEGER_OBJ)
    var expiration: Int? = null
    @DatabaseField(columnName = SIZE, dataType = DataType.INTEGER_OBJ)
    var size: Int? = null

    companion object {
        const val TABLE_NAME = "ATAK_catalog"
        const val KEY = "key"
        const val ACCESS = "access"
        const val EXPIRATION = "expiration"
        const val SIZE = "size"
    }
}