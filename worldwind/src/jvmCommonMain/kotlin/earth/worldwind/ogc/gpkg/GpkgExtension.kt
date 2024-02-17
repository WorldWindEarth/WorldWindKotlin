package earth.worldwind.ogc.gpkg

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import java.io.Serializable

/**
 * CREATE TABLE gpkg_extensions (
 *   table_name TEXT,
 *   column_name TEXT,
 *   extension_name TEXT NOT NULL,
 *   definition TEXT NOT NULL,
 *   scope TEXT NOT NULL,
 *   CONSTRAINT ge_tce UNIQUE (table_name, column_name, extension_name)
 * );
 */
@DatabaseTable(tableName = "gpkg_extensions")
class GpkgExtension : Serializable {
    @DatabaseField(columnName = TABLE_NAME, dataType = DataType.STRING, uniqueCombo = true)
    var tableName: String? = null
    @DatabaseField(columnName = COLUMN_NAME, dataType = DataType.STRING, uniqueCombo = true)
    var columnName: String? = null
    @DatabaseField(columnName = EXTENSION_NAME, dataType = DataType.STRING, uniqueCombo = true, canBeNull = false)
    lateinit var extensionName: String
    @DatabaseField(columnName = DEFINITION, dataType = DataType.STRING, canBeNull = false)
    lateinit var definition: String
    @DatabaseField(columnName = SCOPE, dataType = DataType.STRING, canBeNull = false)
    lateinit var scope: String

    companion object {
        const val TABLE_NAME = "table_name"
        const val COLUMN_NAME = "column_name"
        const val EXTENSION_NAME = "extension_name"
        const val DEFINITION = "definition"
        const val SCOPE = "scope"
    }
}