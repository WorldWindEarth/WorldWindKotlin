package earth.worldwind.ogc.gpkg

const val SPATIAL_REF_SYS = "gpkg_spatial_ref_sys"
const val CONTENTS = "gpkg_contents"
const val TILE_MATRIX_SET = "gpkg_tile_matrix_set"
const val TILE_MATRIX = "gpkg_tile_matrix"
const val EXTENSIONS = "gpkg_extensions"
const val GRIDDED_COVERAGE_ANCILLARY = "gpkg_2d_gridded_coverage_ancillary"
const val GRIDDED_TILE_ANCILLARY = "gpkg_2d_gridded_tile_ancillary"
const val WEB_SERVICE = "gpkg_web_service"

expect open class GeoPackage(pathName: String, isReadOnly: Boolean = true): AbstractGeoPackage