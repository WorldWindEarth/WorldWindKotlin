package earth.worldwind.ogc.gpkg

class GpkgTileUserData(
    override val container: AbstractGeoPackage,
    val id: Int,
    val zoomLevel: Int,
    val tileColumn: Int,
    val tileRow: Int,
    var tileData: ByteArray
): GpkgEntry()