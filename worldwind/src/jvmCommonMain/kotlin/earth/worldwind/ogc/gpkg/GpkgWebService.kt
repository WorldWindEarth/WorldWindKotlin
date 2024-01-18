package earth.worldwind.ogc.gpkg

class GpkgWebService(
    override val container: AbstractGeoPackage,
    val tableName: String,
    val type: String,
    val address: String,
    val metadata: String? = null,
    val layerName: String? = null,
    val outputFormat: String,
    val isTransparent: Boolean = false // For elevation coverages this attribute is always false
): GpkgEntry()