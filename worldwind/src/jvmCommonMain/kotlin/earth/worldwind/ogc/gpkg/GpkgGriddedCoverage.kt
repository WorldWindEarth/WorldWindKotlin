package earth.worldwind.ogc.gpkg

class GpkgGriddedCoverage(
    override val container: AbstractGeoPackage,
    val id: Int = -1,
    val tileMatrixSetName: String,
    val datatype: String = "integer",
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val precision: Double = 1.0,
    val dataNull: Double?,
    val gridCellEncoding: String = "grid-value-is-center",
    val uom: String? = null,
    val fieldName: String = "Height",
    val quantityDefinition: String = "Height"
): GpkgEntry()