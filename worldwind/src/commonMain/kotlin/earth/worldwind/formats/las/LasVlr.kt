package earth.worldwind.formats.las

import earth.worldwind.formats.BinaryDataView
import earth.worldwind.geom.coords.Hemisphere

/**
 * Coordinate reference system detected from a LAS file's GeoKeyDirectory VLR. Only the cases
 * the [earth.worldwind.layer.pointcloud.PointCloudLayer] can georeference without an external
 * projection library are modelled; anything else surfaces as [Unknown] so the caller can
 * supply a projection hook.
 */
sealed interface LasCrs {
    /** X = longitude°, Y = latitude°, Z = height (m). */
    object Geographic : LasCrs
    /** X = easting (m), Y = northing (m), Z = height (m), on the given WGS84 UTM zone. */
    data class Utm(val zone: Int, val hemisphere: Hemisphere) : LasCrs
    /** CRS present but not one we reproject natively; [epsg] is the projected-CS code if known. */
    data class Unknown(val epsg: Int?) : LasCrs
    /** No GeoKey VLR at all. */
    object Absent : LasCrs
}

/** One LASzip item record (type/size/version) from the compression VLR. */
data class LaszipItem(val type: Int, val size: Int, val version: Int)

/**
 * Parsed LASzip VLR — the recipe the [LazChunkDecoder] needs: compressor kind, chunk size,
 * and the ordered item list that makes up each compressed point record.
 */
class LaszipVlr(
    val compressor: Int,
    val chunkSize: Int,
    val items: List<LaszipItem>,
) {
    companion object {
        const val COMPRESSOR_POINTWISE = 1
        const val COMPRESSOR_POINTWISE_CHUNKED = 2
        const val COMPRESSOR_LAYERED_CHUNKED = 3

        // Item types we recognise (ASPRS/LASzip enumeration).
        const val ITEM_BYTE = 0
        const val ITEM_POINT10 = 6
        const val ITEM_GPSTIME11 = 7
        const val ITEM_RGB12 = 8
        const val ITEM_WAVEPACKET13 = 9
        const val ITEM_POINT14 = 10
        const val ITEM_RGB14 = 11
        const val ITEM_RGBNIR14 = 12
        const val ITEM_WAVEPACKET14 = 13
        const val ITEM_BYTE14 = 14
    }
}

/** Result of walking every VLR once: the detected CRS and the LASzip VLR (null when uncompressed). */
class LasVlrData(val crs: LasCrs, val laszip: LaszipVlr?)

/**
 * Walks the Variable Length Records that follow the public header, extracting the two we care
 * about: the GeoKeyDirectory (CRS) and the LASzip compression recipe. Unknown records are
 * skipped by their declared length. Extended (1.4) VLRs are not walked — the LASzip and GeoKey
 * records always live in the standard VLR block.
 */
object LasVlrParser {
    private const val VLR_HEADER_SIZE = 54
    private const val USER_ID_LASZIP = "laszip encoded"
    private const val USER_ID_PROJECTION = "LASF_Projection"
    private const val RECORD_GEO_KEY_DIRECTORY = 34735

    // GeoKey ids we read.
    private const val KEY_GT_MODEL_TYPE = 1024
    private const val KEY_PROJECTED_CS_TYPE = 3072
    private const val MODEL_TYPE_PROJECTED = 1
    private const val MODEL_TYPE_GEOGRAPHIC = 2

    fun parse(bytes: ByteArray, header: LasHeader): LasVlrData {
        val v = BinaryDataView(bytes)
        var offset = header.headerSize
        var crs: LasCrs = LasCrs.Absent
        var laszip: LaszipVlr? = null

        for (i in 0 until header.numVlrs) {
            if (offset + VLR_HEADER_SIZE > bytes.size) break
            val userId = readFixedString(v, offset + 2, 16)
            val recordId = v.getUint16(offset + 18, littleEndian = true)
            val recordLength = v.getUint16(offset + 20, littleEndian = true)
            val dataOffset = offset + VLR_HEADER_SIZE

            if (userId == USER_ID_LASZIP) {
                laszip = parseLaszipVlr(v, dataOffset)
            } else if (userId == USER_ID_PROJECTION && recordId == RECORD_GEO_KEY_DIRECTORY) {
                crs = parseGeoKeys(v, dataOffset, recordLength)
            }

            offset = dataOffset + recordLength
        }
        return LasVlrData(crs, laszip)
    }

    private fun parseLaszipVlr(v: BinaryDataView, dataOffset: Int): LaszipVlr {
        val compressor = v.getUint16(dataOffset, littleEndian = true)
        val chunkSize = v.getUint32(dataOffset + 12, littleEndian = true).toInt()
        val numItems = v.getUint16(dataOffset + 32, littleEndian = true)
        val items = ArrayList<LaszipItem>(numItems)
        var itemOffset = dataOffset + 34
        for (i in 0 until numItems) {
            items.add(
                LaszipItem(
                    type = v.getUint16(itemOffset, littleEndian = true),
                    size = v.getUint16(itemOffset + 2, littleEndian = true),
                    version = v.getUint16(itemOffset + 4, littleEndian = true),
                )
            )
            itemOffset += 6
        }
        return LaszipVlr(compressor, chunkSize, items)
    }

    /** GeoKeyDirectory is a flat uint16 array: [dirVer, keyRev, minorRev, numKeys] then
     *  numKeys × [keyId, tiffTagLocation, count, valueOffset]. We only need model type + the
     *  projected-CS EPSG, and map the WGS84 UTM EPSG ranges to a [LasCrs.Utm]. */
    private fun parseGeoKeys(v: BinaryDataView, dataOffset: Int, recordLength: Int): LasCrs {
        if (recordLength < 8) return LasCrs.Unknown(null)
        val numKeys = v.getUint16(dataOffset + 6, littleEndian = true)
        var modelType = 0
        var projectedCs = 0
        var keyOffset = dataOffset + 8
        for (i in 0 until numKeys) {
            if (keyOffset + 8 > dataOffset + recordLength) break
            val keyId = v.getUint16(keyOffset, littleEndian = true)
            val tiffTagLocation = v.getUint16(keyOffset + 2, littleEndian = true)
            val valueOffset = v.getUint16(keyOffset + 6, littleEndian = true)
            // Values we need are inline (tiffTagLocation == 0); indirect string/double keys aren't.
            if (tiffTagLocation == 0) when (keyId) {
                KEY_GT_MODEL_TYPE -> modelType = valueOffset
                KEY_PROJECTED_CS_TYPE -> projectedCs = valueOffset
            }
            keyOffset += 8
        }

        return when (modelType) {
            MODEL_TYPE_GEOGRAPHIC -> LasCrs.Geographic
            MODEL_TYPE_PROJECTED -> utmFromEpsg(projectedCs) ?: LasCrs.Unknown(projectedCs.takeIf { it != 0 })
            // Model type absent/unknown: still try the projected-CS code if one was given.
            else -> if (projectedCs != 0) utmFromEpsg(projectedCs) ?: LasCrs.Unknown(projectedCs) else LasCrs.Unknown(null)
        }
    }

    /** UTM EPSG codes for the datums that are within a metre or two of WGS84 (fine for
     *  visualisation): WGS84 (326zz N / 327zz S), NAD83 (269zz N), and ETRS89 (258zz N). */
    private fun utmFromEpsg(epsg: Int): LasCrs.Utm? = when (epsg) {
        in 32601..32660 -> LasCrs.Utm(epsg - 32600, Hemisphere.N)
        in 32701..32760 -> LasCrs.Utm(epsg - 32700, Hemisphere.S)
        in 26901..26923 -> LasCrs.Utm(epsg - 26900, Hemisphere.N) // NAD83
        in 25828..25838 -> LasCrs.Utm(epsg - 25800, Hemisphere.N) // ETRS89
        else -> null
    }

    private fun readFixedString(v: BinaryDataView, offset: Int, length: Int): String = buildString {
        for (i in 0 until length) {
            val c = v.getUint8(offset + i)
            if (c == 0) break
            append(c.toChar())
        }
    }
}
