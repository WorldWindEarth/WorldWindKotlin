package earth.worldwind.ogc.gpkg

class GpkgTileMatrix(
    override val container: AbstractGeoPackage,
    val tableName: String,
    val zoomLevel: Int,
    val matrixWidth: Int,
    val matrixHeight: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val pixelXSize: Double,
    val pixelYSize: Double,
): GpkgEntry()