package earth.worldwind.formats.las

import earth.worldwind.formats.BinaryDataView

/**
 * Parsed LAS/LAZ public header block. Covers LAS versions 1.0–1.4 — later versions extend
 * the block but keep every field below at a stable offset. All multi-byte fields are
 * little-endian per the ASPRS spec; reads go through [BinaryDataView].
 *
 * LAZ files carry the same header as their LAS source with the LASzip compression bit set in
 * the point-data-record-format byte — [isCompressed] strips it and [pointDataFormat] is always
 * the underlying ASPRS format (0–10).
 */
class LasHeader private constructor(
    val versionMajor: Int,
    val versionMinor: Int,
    /** Underlying ASPRS point format 0–10, with the LASzip compression bit removed. */
    val pointDataFormat: Int,
    /** True when the point records are LASzip-compressed (needs a [LazChunkDecoder]). */
    val isCompressed: Boolean,
    /** Bytes per point record, including any trailing extra-bytes the spec fields don't name. */
    val pointDataRecordLength: Int,
    /** Total point count — the 1.4 64-bit field when present, else the legacy 32-bit field. */
    val pointCount: Long,
    val globalEncoding: Int,
    val scaleX: Double, val scaleY: Double, val scaleZ: Double,
    val offsetX: Double, val offsetY: Double, val offsetZ: Double,
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double,
    /** Byte offset from the file start to the first point record. */
    val offsetToPointData: Int,
    /** Number of Variable Length Records following the header. */
    val numVlrs: Int,
    /** Byte offset from the file start to the first VLR (= header size). */
    val headerSize: Int,
) {
    /** GPS-time is standard-adjusted (bit 0) rather than week-time. Only relevant to LAZ. */
    val isGpsStandardTime: Boolean get() = (globalEncoding and 0x01) != 0

    companion object {
        const val SIGNATURE = "LASF"
        /** LASzip sets this bit in the point-data-record-format byte on compressed files. */
        private const val COMPRESSION_BIT = 0x80

        fun parse(bytes: ByteArray): LasHeader {
            require(bytes.size >= 227) { "LAS file too small for a header (${bytes.size} bytes)" }
            val v = BinaryDataView(bytes)
            val signature = buildString { for (i in 0 until 4) append(v.getUint8(i).toChar()) }
            require(signature == SIGNATURE) { "not a LAS/LAZ file (signature '$signature')" }

            val versionMajor = v.getUint8(24)
            val versionMinor = v.getUint8(25)
            val globalEncoding = v.getUint16(6, littleEndian = true)
            val headerSize = v.getUint16(94, littleEndian = true)
            val offsetToPointData = v.getUint32(96, littleEndian = true).toInt()
            val numVlrs = v.getUint32(100, littleEndian = true).toInt()

            val rawFormat = v.getUint8(104)
            val isCompressed = (rawFormat and COMPRESSION_BIT) != 0
            val pointDataFormat = rawFormat and 0x3F
            val pointDataRecordLength = v.getUint16(105, littleEndian = true)

            val legacyCount = v.getUint32(107, littleEndian = true)
            // LAS 1.4 moved the authoritative count to a 64-bit field; formats 6–10 zero the
            // legacy field, so prefer the 64-bit value whenever the extended header is present.
            val pointCount = if ((versionMajor > 1 || versionMinor >= 4) && bytes.size >= 255) {
                val extended = v.getInt64(247, littleEndian = true)
                if (extended != 0L) extended else legacyCount
            } else legacyCount

            return LasHeader(
                versionMajor = versionMajor,
                versionMinor = versionMinor,
                pointDataFormat = pointDataFormat,
                isCompressed = isCompressed,
                pointDataRecordLength = pointDataRecordLength,
                pointCount = pointCount,
                globalEncoding = globalEncoding,
                scaleX = v.getFloat64(131, true), scaleY = v.getFloat64(139, true), scaleZ = v.getFloat64(147, true),
                offsetX = v.getFloat64(155, true), offsetY = v.getFloat64(163, true), offsetZ = v.getFloat64(171, true),
                maxX = v.getFloat64(179, true), minX = v.getFloat64(187, true),
                maxY = v.getFloat64(195, true), minY = v.getFloat64(203, true),
                maxZ = v.getFloat64(211, true), minZ = v.getFloat64(219, true),
                offsetToPointData = offsetToPointData,
                numVlrs = numVlrs,
                headerSize = headerSize,
            )
        }
    }
}
