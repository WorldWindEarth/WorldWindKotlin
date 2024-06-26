package earth.worldwind.formats.geotiff

object GeoTiffConstants {
    const val MODEL_PIXEL_SCALE = 33550
    const val MODEL_TRANSFORMATION = 34264
    const val MODEL_TIEPOINT = 33922
    const val GEO_KEY_DIRECTORY = 34735
    const val GEO_DOUBLE_PARAMS = 34736
    const val GEO_ASCII_PARAMS = 34737

    // GDAL
    const val GDAL_NODATA = 42113
    const val GDAL_METADATA = 42112

    const val GT_MODEL_TYPE_GEO_KEY = 1024
    const val GT_RASTER_TYPE_GEO_KEY = 1025
    const val GT_CITATION_GEO_KEY = 1026

    // Geographic CS Parameter Keys
    const val GEOGRAPHIC_TYPE_GEO_KEY = 2048
    const val GEOG_CITATION_GEO_KEY = 2049
    const val GEOG_GEODETIC_DATUM_GEO_KEY = 2050
    const val GEOG_PRIME_MERIDIAN_GEO_KEY = 2051
    const val GEOG_LINEAR_UNITS_GEO_KEY = 2052
    const val GEOG_LINEAR_UNIT_SIZE_GEO_KEY = 2053
    const val GEOG_ANGULAR_UNITS_GEO_KEY = 2054
    const val GEOG_ANGULAR_UNIT_SIZE_GEO_KEY = 2055
    const val GEOG_ELLIPSOID_GEO_KEY = 2056
    const val GEOG_SEMI_MAJOR_AXIS_GEO_KEY = 2057
    const val GEOG_SEMI_MINOR_AXIS_GEO_KEY = 2058
    const val GEOG_INV_FLATTENING_GEO_KEY = 2059
    const val GEOG_AZIMUTH_UNITS_GEO_KEY = 2060
    const val GEOG_PRIME_MERIDIAN_LONG_GEO_KEY = 2061
    const val GEOG_TOWGS84_GEO_KEY = 2062

    // Projected CS Parameter Keys
    const val PROJECTED_CS_TYPE_GEO_KEY = 3072
    const val PCS_CITATION_GEO_KEY = 3073
    const val PROJECTION_GEO_KEY = 3074
    const val PROJ_COORD_TRANS_GEO_KEY = 3075
    const val PROJ_LINEAR_UNITS_GEO_KEY = 3076
    const val PROJ_LINEAR_UNIT_SIZE_GEO_KEY = 3077
    const val PROJ_STD_PARALLEL_1_GEO_KEY = 3078
    const val PROJ_STD_PARALLEL_2_GEO_KEY = 3079
    const val PROJ_NAT_ORIGIN_LONG_GEO_KEY = 3080
    const val PROJ_NAT_ORIGIN_LAT_GEO_KEY = 3081
    const val PROJ_FALSE_EASTING_GEO_KEY = 3082
    const val PROJ_FALSE_NORTHING_GEO_KEY = 3083
    const val PROJ_FALSE_ORIGIN_LONG_GEO_KEY = 3084
    const val PROJ_FALSE_ORIGIN_LAT_GEO_KEY = 3085
    const val PROJ_FALSE_ORIGIN_EASTING_GEO_KEY = 3086
    const val PROJ_FALSE_ORIGIN_NORTHING_GEO_KEY = 3087
    const val PROJ_CENTER_LONG_GEO_KEY = 3088
    const val PROJ_CENTER_LAT_GEO_KEY = 3089
    const val PROJ_CENTER_EASTING_GEO_KEY = 3090
    const val PROJ_CENTER_NORTHING_GEO_KEY = 3091
    const val PROJ_SCALE_AT_NAT_ORIGIN_GEO_KEY = 3092
    const val PROJ_SCALE_AT_CENTER_GEO_KEY = 3093
    const val PROJ_AZIMUTH_ANGLE_GEO_KEY = 3094
    const val PROJ_STRAIGHT_VERT_POLE_LONG_GEO_KEY = 3095

    // Vertical CS Keys
    const val VERTICAL_CS_TYPE_GEO_KEY = 4096
    const val VERTICAL_CITATION_GEO_KEY = 4097
    const val VERTICAL_DATUM_GEO_KEY = 4098
    const val VERTICAL_UNITS_GEO_KEY = 4099
}