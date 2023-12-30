package earth.worldwind.ogc.gpkg

class GpkgExtension(
    override val container: AbstractGeoPackage,
    val tableName: String? = null,
    val columnName: String? = null,
    val extensionName: String,
    val definition: String,
    val scope: String,
): GpkgEntry()