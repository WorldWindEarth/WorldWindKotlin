package earth.worldwind.ogc.gpkg

class GpkgTileMatrixSet(
    override val container: AbstractGeoPackage,
    val tableName: String,
    val srsId: Int,
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double
): GpkgEntry()