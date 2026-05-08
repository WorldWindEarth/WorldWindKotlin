package earth.worldwind.formats.geotiff

import earth.worldwind.formats.geotiff.GeoTiffConstants.GEO_ASCII_PARAMS
import earth.worldwind.formats.geotiff.GeoTiffConstants.GEO_DOUBLE_PARAMS
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage

class GeoTiffKeyEntry(
    keyId: Int,
    private val tiffTagLocation: Int,
    private val count: Int,
    private val valueOffset: Int
) {
    init {
        require(keyId != 0) { logMessage(ERROR, "GeoTiffKeyEntry", "constructor", "Missing key ID") }
    }

    fun getGeoKeyValue(geoDoubleParamsValue: List<Double>): Number? = when (tiffTagLocation) {
        0 -> valueOffset
        GEO_ASCII_PARAMS -> error(logMessage(ERROR, "GeoTiffKeyEntry", "getGeoKeyValue", "Invalid key type"))
        GEO_DOUBLE_PARAMS -> geoDoubleParamsValue[valueOffset]
        else -> null
    }

    fun getAsciiGeoKeyValue(geoAsciiParamsValue: String?): String? {
        require(tiffTagLocation == GEO_ASCII_PARAMS) {
            logMessage(ERROR, "GeoTiffKeyEntry", "getAsciiGeoKeyValue", "Invalid key type")
        }
        return geoAsciiParamsValue?.let {
            val keyValue = StringBuilder()
            for (i in valueOffset until count - 1) keyValue.append(it[i])
            if (it[count - 1] != '|') keyValue.append(it[count - 1])
            keyValue.toString()
        }
    }
}