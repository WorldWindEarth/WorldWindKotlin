package earth.worldwind.ogc.gpkg

class GpkgGriddedTile(
    override val container: AbstractGeoPackage,
    val id: Int,
    val tpudtName: String,
    val tpudtId: Int,
    var scale: Float = 1.0f,
    var offset: Float = 0.0f,
    var min: Float?,
    var max: Float?,
    var mean: Float?,
    var stdDev: Float?
): GpkgEntry()