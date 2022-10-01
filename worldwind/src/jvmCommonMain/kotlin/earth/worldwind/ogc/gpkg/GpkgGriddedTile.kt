package earth.worldwind.ogc.gpkg

class GpkgGriddedTile(
    override val container: AbstractGeoPackage,
    val id: Int,
    val tpudtName: String,
    val tpudtId: Int,
    var scale: Double,
    var offset: Double,
    var min: Double?,
    var max: Double?,
    var mean: Double?,
    var stdDev: Double?
): GpkgEntry()