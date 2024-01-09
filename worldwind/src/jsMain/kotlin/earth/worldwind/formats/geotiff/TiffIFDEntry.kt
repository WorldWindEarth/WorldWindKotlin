package earth.worldwind.formats.geotiff

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import org.khronos.webgl.DataView

class TiffIFDEntry(
    val tag: Int,
    private val type: Int,
    private val count: Int,
    private val valueOffset: Int,
    private val geoTiffData: DataView,
    private val isLittleEndian: Boolean
) {
    fun getIFDEntryValue(allowAscii: Boolean = false): List<Number> {
        require (allowAscii || type != TiffConstants.Type.ASCII) {
            logMessage(ERROR, "TiffIFDEntry", "getIFDEntryValue", "Invalid Key Type - ASCII not allowed.")
        }
        val length = getIFDTypeLength()
        return if (length * count <= 4) listOf(if (!isLittleEndian) valueOffset shr ((4 - length) * 8) else valueOffset)
        else getIFDEntryValueLongerThan4(length)
    }

    fun getIFDEntryAsciiValue(): String {
        require(type == TiffConstants.Type.ASCII) {
            logMessage(ERROR, "TiffIFDEntry", "getIFDEntryValue","Invalid Key Type - ASCII required.")
        }
        return getIFDEntryValue(true).map { it.toChar() }.joinToString("")
    }

    private fun getIFDTypeLength() = when (type) {
        TiffConstants.Type.BYTE, TiffConstants.Type.ASCII, TiffConstants.Type.SBYTE, TiffConstants.Type.UNDEFINED -> 1
        TiffConstants.Type.SHORT, TiffConstants.Type.SSHORT -> 2
        TiffConstants.Type.LONG, TiffConstants.Type.SLONG, TiffConstants.Type.FLOAT -> 4
        TiffConstants.Type.RATIONAL, TiffConstants.Type.SRATIONAL, TiffConstants.Type.DOUBLE -> 8
        else -> -1
    }

    private fun getIFDEntryValueLongerThan4(ifdTypeLength: Int): List<Number> {
        val ifdValues = mutableListOf<Number>()
        for (i in 0 until count) {
            val indexOffset = ifdTypeLength * i
            if (ifdTypeLength >= 8) when (type) {
                TiffConstants.Type.RATIONAL, TiffConstants.Type.SRATIONAL -> {
                    ifdValues.add(GeoTiffUtil.getBytes(geoTiffData, valueOffset + indexOffset, 4, isLittleEndian))
                    ifdValues.add(GeoTiffUtil.getBytes(geoTiffData, valueOffset + indexOffset + 4, 4, isLittleEndian))
                }
                TiffConstants.Type.DOUBLE -> ifdValues.add(GeoTiffUtil.getBytes(geoTiffData, valueOffset + indexOffset, 8, isLittleEndian))
                else -> error(logMessage(ERROR, "TiffIFDEntry", "getIFDEntryValue", "Invalid Type of IFD"))
            } else ifdValues.add(GeoTiffUtil.getBytes(geoTiffData, valueOffset + indexOffset, ifdTypeLength, isLittleEndian))
        }
        return ifdValues
    }
}
