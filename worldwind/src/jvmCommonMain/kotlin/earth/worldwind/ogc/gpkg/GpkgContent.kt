package earth.worldwind.ogc.gpkg

class GpkgContent(
    override val container: AbstractGeoPackage,
    val tableName: String,
    val dataType: String,
    val identifier: String,
    val description: String = "",
    val lastChange: String,
    val minX: Double?,
    val minY: Double?,
    val maxX: Double?,
    val maxY: Double?,
    val srsId: Int?
): GpkgEntry()