package earth.worldwind.ogc.gpkg

class GpkgGriddedTile(
    override val container: AbstractGeoPackage,
    val id: Int,
    val tpudtName: String,
    val tpudtId: Int,
    var scale: Float = 1.0f,
    var offset: Float = 0.0f,
    var min: Float? = null,
    var max: Float? = null,
    var mean: Float? = null,
    var stdDev: Float? = null
): GpkgEntry()