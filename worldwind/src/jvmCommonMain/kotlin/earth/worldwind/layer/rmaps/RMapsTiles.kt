package earth.worldwind.layer.rmaps

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import earth.worldwind.layer.rmaps.RMapsTiles.Companion.TABLE_NAME

@DatabaseTable(tableName = TABLE_NAME)
class RMapsTiles {
    @DatabaseField(columnName = X, dataType = DataType.INTEGER, uniqueCombo = true)
    var x = 0
    @DatabaseField(columnName = Y, dataType = DataType.INTEGER, uniqueCombo = true)
    var y = 0
    @DatabaseField(columnName = Z, dataType = DataType.INTEGER, uniqueCombo = true)
    var z = 0
    @DatabaseField(columnName = S, dataType = DataType.INTEGER, uniqueCombo = true)
    var s = 0
    @DatabaseField(columnName = IMAGE, dataType = DataType.BYTE_ARRAY)
    var image: ByteArray? = null

    companion object {
        const val TABLE_NAME = "tiles"
        const val X = "x"
        const val Y = "y"
        const val Z = "z"
        const val S = "s"
        const val IMAGE = "image"
    }
}