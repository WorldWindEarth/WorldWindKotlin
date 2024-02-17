package earth.worldwind.ogc.gpkg

import com.j256.ormlite.dao.ForeignCollection
import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.field.ForeignCollectionField
import com.j256.ormlite.table.DatabaseTable
import java.io.Serializable
import java.util.*

/**
 * CREATE TABLE gpkg_contents (
 *   table_name TEXT NOT NULL PRIMARY KEY,
 *   data_type TEXT NOT NULL,
 *   identifier TEXT UNIQUE,
 *   description TEXT DEFAULT '',
 *   last_change DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 *   min_x DOUBLE,
 *   min_y DOUBLE,
 *   max_x DOUBLE,
 *   max_y DOUBLE,
 *   srs_id INTEGER,
 *   CONSTRAINT fk_gc_r_srs_id FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id)
 * );
 */
@DatabaseTable(tableName = "gpkg_contents")
class GpkgContent : Serializable {
    @DatabaseField(columnName = TABLE_NAME, dataType = DataType.STRING, canBeNull = false, id = true)
    lateinit var tableName: String
    @DatabaseField(columnName = DATA_TYPE, dataType = DataType.STRING, canBeNull = false)
    lateinit var dataType: String
    @DatabaseField(columnName = IENTIFIER, dataType = DataType.STRING, unique = true)
    lateinit var identifier: String
    @DatabaseField(columnName = DESCRIPTION, dataType = DataType.STRING)
    var description: String = ""
    @DatabaseField(columnName = LAST_CHANGE, dataType = DataType.DATE_STRING, canBeNull = false, format = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    var lastChange: Date = Date()
    @DatabaseField(columnName = MIN_X, dataType = DataType.DOUBLE_OBJ)
    var minX: Double? = null
    @DatabaseField(columnName = MIN_Y, dataType = DataType.DOUBLE_OBJ)
    var minY: Double? = null
    @DatabaseField(columnName = MAX_X, dataType = DataType.DOUBLE_OBJ)
    var maxX: Double? = null
    @DatabaseField(columnName = MAX_Y, dataType = DataType.DOUBLE_OBJ)
    var maxY: Double? = null
    @DatabaseField(columnName = SRS, dataType = DataType.SERIALIZABLE, foreign = true)
    var srs: GpkgSpatialReferenceSystem? = null
    @ForeignCollectionField(eager = true)
    var tileMatrices: ForeignCollection<GpkgTileMatrix>? = null

    companion object {
        const val TABLE_NAME = "table_name"
        const val DATA_TYPE = "data_type"
        const val IENTIFIER = "identifier"
        const val DESCRIPTION = "description"
        const val LAST_CHANGE = "last_change"
        const val MIN_X = "min_x"
        const val MIN_Y = "min_y"
        const val MAX_X = "max_x"
        const val MAX_Y = "max_y"
        const val SRS = "srs_id"
    }
}