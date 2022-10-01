package earth.worldwind.ogc.gpkg

class GpkgSpatialReferenceSystem(
    override val container: AbstractGeoPackage,
    val srsName: String,
    val srsId: Int,
    val organization: String,
    val organizationCoordSysId: Int,
    val definition: String,
    val description: String?
): GpkgEntry()