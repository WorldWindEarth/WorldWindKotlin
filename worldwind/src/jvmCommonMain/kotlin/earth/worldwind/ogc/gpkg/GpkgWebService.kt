package earth.worldwind.ogc.gpkg

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import java.io.Serializable

/**
 * CREATE TABLE IF NOT EXISTS gpkg_web_service (
 *   table_name TEXT NOT NULL PRIMARY KEY,
 *   service_type TEXT NOT NULL,
 *   service_address TEXT NOT NULL,
 *   service_metadata TEXT,
 *   layer_name TEXT,
 *   output_format TEXT NOT NULL,
 *   is_transparent SMALLINT DEFAULT 0
 * );
 */
@DatabaseTable(tableName = "gpkg_web_service")
class GpkgWebService : Serializable {
    @DatabaseField(columnName = TABLE_NAME, dataType = DataType.STRING, canBeNull = false, id = true)
    lateinit var tableName: String
    @DatabaseField(columnName = SERVICE_TYPE, dataType = DataType.STRING, canBeNull = false)
    lateinit var type: String
    @DatabaseField(columnName = SERVICE_ADDRESS, dataType = DataType.STRING, canBeNull = false)
    lateinit var address: String
    @DatabaseField(columnName = SERVICE_METADATA, dataType = DataType.STRING)
    var metadata: String? = null
    @DatabaseField(columnName = LAYER_NAME, dataType = DataType.STRING)
    var layerName: String? = null
    @DatabaseField(columnName = OUTPUT_FORMAT, dataType = DataType.STRING)
    lateinit var outputFormat: String
    @DatabaseField(columnName = IS_TRANSPARENT, dataType = DataType.BOOLEAN_INTEGER)
    var isTransparent: Boolean = false // For elevation coverages this attribute is always false

    companion object {
        const val TABLE_NAME = "table_name"
        const val SERVICE_TYPE = "service_type"
        const val SERVICE_ADDRESS = "service_address"
        const val SERVICE_METADATA = "service_metadata"
        const val LAYER_NAME = "layer_name"
        const val OUTPUT_FORMAT = "output_format"
        const val IS_TRANSPARENT = "is_transparent"
    }
}