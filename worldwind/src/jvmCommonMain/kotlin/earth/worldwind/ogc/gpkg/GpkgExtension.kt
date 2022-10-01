package earth.worldwind.ogc.gpkg

class GpkgExtension(
    override val container: AbstractGeoPackage,
    val tableName: String?,
    val columnName: String?,
    val extensionName: String,
    val definition: String,
    val scope: String,
): GpkgEntry()