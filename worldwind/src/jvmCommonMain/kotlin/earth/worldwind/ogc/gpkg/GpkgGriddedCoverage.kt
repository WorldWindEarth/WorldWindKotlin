package earth.worldwind.ogc.gpkg

class GpkgGriddedCoverage(
    override val container: AbstractGeoPackage,
    val id: Int = -1,
    val tileMatrixSetName: String,
    val datatype: String = "integer",
    val scale: Float = 1.0f,
    val offset: Float = 0.0f,
    val precision: Float = 1.0f,
    val dataNull: Float? = null,
    val gridCellEncoding: String = "grid-value-is-center",
    val uom: String? = null,
    val fieldName: String = "Height",
    val quantityDefinition: String = "Height"
): GpkgEntry()