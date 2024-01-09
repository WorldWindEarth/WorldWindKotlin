package earth.worldwind.formats.geotiff

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import org.khronos.webgl.DataView

object GeoTiffUtil {

    fun getBytes(
        geoTiffData: DataView, byteOffset: Int, numOfBytes: Int, isLittleEndian: Boolean, isSigned: Boolean = false
    ): Number = when {
        numOfBytes <= 0 -> error(logMessage(ERROR, "GeoTiffUtil", "getBytes", "No bytes requested"))
        numOfBytes <= 1 -> if (isSigned) geoTiffData.getInt8(byteOffset) else geoTiffData.getUint8(byteOffset)
        numOfBytes <= 2 -> if (isSigned) geoTiffData.getInt16(byteOffset, isLittleEndian) else geoTiffData.getUint16(byteOffset, isLittleEndian)
        numOfBytes <= 3 -> if (isSigned) geoTiffData.getInt32(byteOffset, isLittleEndian) ushr 8 else geoTiffData.getUint32(byteOffset, isLittleEndian) ushr 8
        numOfBytes <= 4 -> if (isSigned) geoTiffData.getInt32(byteOffset, isLittleEndian) else geoTiffData.getUint32(byteOffset, isLittleEndian)
        numOfBytes <= 8 -> geoTiffData.getFloat64(byteOffset, isLittleEndian)
        else -> error(logMessage(ERROR, "GeoTiffUtil", "getBytes", "Too many bytes requested"))
    }

    fun getSampleBytes(
        geoTiffData: DataView, byteOffset: Int, numOfBytes: Int, sampleFormat: Int, isLittleEndian: Boolean
    ): Number = when (sampleFormat) {
        TiffConstants.SampleFormat.UNSIGNED -> getBytes(geoTiffData, byteOffset, numOfBytes, isLittleEndian, false)
        TiffConstants.SampleFormat.SIGNED -> getBytes(geoTiffData, byteOffset, numOfBytes, isLittleEndian, true)
        TiffConstants.SampleFormat.IEEE_FLOAT -> when (numOfBytes) {
            3 -> geoTiffData.getFloat32(byteOffset, isLittleEndian).toInt() ushr 8
            4 -> geoTiffData.getFloat32(byteOffset, isLittleEndian)
            8 -> geoTiffData.getFloat64(byteOffset, isLittleEndian)
            else -> error(logMessage(
                ERROR, "GeoTiffUtil", "getSampleBytes",
                "Do not attempt to parse the data not handled: $numOfBytes"
            ))
        }
        else -> getBytes(geoTiffData, byteOffset, numOfBytes, isLittleEndian, false)
    }
}