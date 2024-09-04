package earth.worldwind.layer.rmaps

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import earth.worldwind.layer.rmaps.RMapsInfo.Companion.TABLE_NAME

@DatabaseTable(tableName = TABLE_NAME)
class RMapsInfo {
    @DatabaseField(columnName = MIN_ZOOM, dataType = DataType.INTEGER)
    var minzoom = 0
    @DatabaseField(columnName = MAX_ZOOM, dataType = DataType.INTEGER)
    var maxzoom = 0

    companion object {
        const val TABLE_NAME = "info"
        const val MIN_ZOOM = "minzoom"
        const val MAX_ZOOM = "maxzoom"
    }
}