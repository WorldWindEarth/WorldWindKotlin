package earth.worldwind.ogc.gpkg

import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable
import java.io.Serializable

/**
 * CREATE TABLE gpkg_spatial_ref_sys (
 *   srs_name TEXT NOT NULL,
 *   srs_id INTEGER PRIMARY KEY,
 *   organization TEXT NOT NULL,
 *   organization_coordsys_id INTEGER NOT NULL,
 *   definition TEXT NOT NULL,
 *   description TEXT
 * );
 */
@DatabaseTable(tableName = "gpkg_spatial_ref_sys")
class GpkgSpatialReferenceSystem : Serializable {
    @DatabaseField(columnName = NAME, dataType = DataType.STRING, canBeNull = false)
    lateinit var name: String
    @DatabaseField(columnName = ID, dataType = DataType.INTEGER, id = true)
    var id: Int = 0
    @DatabaseField(columnName = ORGANIZATION, dataType = DataType.STRING, canBeNull = false)
    lateinit var organization: String
    @DatabaseField(columnName = ORGANIZATION_COORDSYS_ID, dataType = DataType.INTEGER, canBeNull = false)
    var organizationSysId: Int = 0
    @DatabaseField(columnName = DEFINITION, dataType = DataType.STRING, canBeNull = false)
    lateinit var definition: String
    @DatabaseField(columnName = DESCRIPTION, dataType = DataType.STRING)
    var description: String? = null

    companion object {
        const val NAME = "srs_name"
        const val ID = "srs_id"
        const val ORGANIZATION = "organization"
        const val ORGANIZATION_COORDSYS_ID = "organization_coordsys_id"
        const val DEFINITION = "definition"
        const val  DESCRIPTION = "description"
    }
}