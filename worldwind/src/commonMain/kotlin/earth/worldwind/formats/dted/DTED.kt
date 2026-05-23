package earth.worldwind.formats.dted

import earth.worldwind.formats.BinaryDataView
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage

/**
 * Cross-platform DTED (MIL-PRF-89020B) reader. Decodes the User Header Label (UHL),
 * Data Set Identification (DSI), and Accuracy (ACC) records to recover the tile's
 * geographic extent, then walks the per-column elevation records — validating the
 * MIL-spec 32-bit unsigned-byte-sum checksum on each — and assembles a row-major
 * elevation grid in the WorldWind convention (origin at the north-west corner,
 * row 0 = northernmost latitude, column 0 = westernmost longitude).
 *
 * DTED's null sentinel `-32767` and any value outside `[-12000, +9000]` (per
 * MIL-PRF-89020B §3.11.2 / §3.11.3) are normalised to [Short.MIN_VALUE] so callers
 * share the same missing-data convention used by the rest of the elevation
 * pipeline (PNG/BIL/GeoTIFF decoders).
 *
 * Three entry points cover the common needs:
 * - [DTED.parse] (or `DTED(bytes)` via [Companion.invoke]) — full parse from a byte array.
 * - [DTED.readMetadata] — UHL/DSI/ACC only (~3.4 KB) when you just need the sector / level.
 * - Platform-specific overloads (e.g. `DTED.read(file)` on JVM) stream column records
 *   from disk so peak memory stays at one record (~7 KB on .dt2) instead of ~26 MB.
 */
class DTED internal constructor(
    /** Geographic extent of the tile. DTED tiles always cover 1° × 1° with the
     *  origin at the south-west corner. */
    val sector: Sector,
    /** North-west corner (WorldWind's "origin" convention). */
    val origin: Location,
    /** Number of longitude posts (columns). */
    val width: Int,
    /** Number of latitude posts (rows). */
    val height: Int,
    /** DTED series level (0, 1, or 2) when present in the DSI record. */
    val level: Int?,
    /** Security classification ("Unclassified", "Restricted", "Confidential",
     *  "Secret") when present in the UHL or DSI record. */
    val classLevel: String?,
    /** Elevation grid in row-major order, north-to-south, west-to-east.
     *  Missing posts are [Short.MIN_VALUE]. */
    val elevations: ShortArray,
    /** Lowest valid elevation in metres, or `0.0` if the tile has no valid posts. */
    val minElevation: Double,
    /** Highest valid elevation in metres, or `0.0` if the tile has no valid posts. */
    val maxElevation: Double,
) {
    /** Header-only view (sector, dimensions, level, classification). Returned by
     *  [Companion.readMetadata] and useful for catalogue / indexing pipelines that
     *  don't need the elevation grid. */
    val metadata: Metadata get() = Metadata(sector, origin, width, height, level, classLevel)

    data class Metadata(
        val sector: Sector,
        val origin: Location,
        val width: Int,
        val height: Int,
        val level: Int?,
        val classLevel: String?,
    )

    companion object {
        const val UHL_SIZE = 80
        const val DSI_SIZE = 648
        const val ACC_SIZE = 2700

        const val UHL_OFFSET = 0
        const val DSI_OFFSET = UHL_OFFSET + UHL_SIZE
        const val ACC_OFFSET = DSI_OFFSET + DSI_SIZE
        const val DATA_OFFSET = ACC_OFFSET + ACC_SIZE

        const val REC_HEADER_SIZE = 8
        const val REC_CHKSUM_SIZE = 4
        /** Per-column record size in bytes for a given latitude post count. */
        fun recordSize(height: Int) = REC_HEADER_SIZE + height * 2 + REC_CHKSUM_SIZE

        const val NODATA_VALUE = -32767
        const val MIN_VALUE = -12000
        const val MAX_VALUE = 9000

        /** Convenience: lets callers write `DTED(bytes)` instead of `DTED.parse(bytes)`. */
        operator fun invoke(bytes: ByteArray): DTED = parse(bytes)

        /** Decode a complete DTED file held in memory. */
        fun parse(bytes: ByteArray): DTED {
            require(bytes.size >= DATA_OFFSET) {
                logMessage(ERROR, "DTED", "parse", "DTED file too short: ${bytes.size}")
            }
            val data = BinaryDataView(bytes)
            val meta = parseHeader(data)
            val rec = recordSize(meta.height)
            require(bytes.size >= DATA_OFFSET + meta.width.toLong() * rec) {
                logMessage(
                    ERROR, "DTED", "parse",
                    "DTED file truncated: expected ${DATA_OFFSET + meta.width.toLong() * rec} bytes, have ${bytes.size}"
                )
            }
            val elevations = ShortArray(meta.width * meta.height)
            for (x in 0 until meta.width) {
                decodeColumn(data, DATA_OFFSET + x * rec, x, meta.width, meta.height, elevations)
            }
            val (minE, maxE) = computeRange(elevations)
            return DTED(
                meta.sector, meta.origin, meta.width, meta.height, meta.level, meta.classLevel,
                elevations, minE, maxE
            )
        }

        /** Parse only the UHL/DSI/ACC headers (~3.4 KB). The byte array may contain
         *  the full file or just the first [DATA_OFFSET] bytes. */
        fun readMetadata(bytes: ByteArray): Metadata {
            require(bytes.size >= DATA_OFFSET) {
                logMessage(ERROR, "DTED", "readMetadata", "DTED header truncated: ${bytes.size}")
            }
            return parseHeader(BinaryDataView(bytes))
        }

        /** Parse the fixed 3428-byte header block sitting at the start of every DTED
         *  file. Shared between [parse] and the platform-specific streaming readers. */
        internal fun parseHeader(data: BinaryDataView): Metadata {
            checkRecordId(data, UHL_OFFSET, "UHL")
            val lon = readAngle(readAscii(data, UHL_OFFSET + 4, 8))
                ?: error(logMessage(ERROR, "DTED", "parseHeader", "Invalid UHL longitude"))
            val lat = readAngle(readAscii(data, UHL_OFFSET + 12, 8))
                ?: error(logMessage(ERROR, "DTED", "parseHeader", "Invalid UHL latitude"))
            val width = readAscii(data, UHL_OFFSET + 47, 4).trim().toInt()
            val height = readAscii(data, UHL_OFFSET + 51, 4).trim().toInt()
            val sector = Sector.fromDegrees(lat.inDegrees, lon.inDegrees, 1.0, 1.0)
            val origin = Location.fromDegrees(lat.inDegrees + 1.0, lon.inDegrees)
            val uhlClass = readClassLevel(readAscii(data, UHL_OFFSET + 32, 3))

            checkRecordId(data, DSI_OFFSET, "DSI")
            val classLevel = uhlClass ?: readClassLevel(readAscii(data, DSI_OFFSET + 3, 1))
            val level = readLevel(readAscii(data, DSI_OFFSET + 59, 5))

            checkRecordId(data, ACC_OFFSET, "ACC")

            return Metadata(sector, origin, width, height, level, classLevel)
        }

        /** Validate the per-record checksum and decode one column of latitude posts,
         *  writing them into [out] in WorldWind row-major order (south→north flipped).
         *  Shared between the byte-array and streaming reader paths. */
        internal fun decodeColumn(
            data: BinaryDataView, recordOffset: Int, columnIndex: Int,
            width: Int, height: Int, out: ShortArray,
        ) {
            val recSize = recordSize(height)
            val checksumOffset = recordOffset + recSize - REC_CHKSUM_SIZE
            var sum = 0
            for (i in recordOffset until checksumOffset) sum += data.getUint8(i)
            val expected = data.getInt32(checksumOffset, littleEndian = false)
            if (sum != expected) error(
                logMessage(
                    ERROR, "DTED", "decodeColumn",
                    "Data record checksum error at column $columnIndex: expected=$expected, actual=$sum"
                )
            )
            val elevStart = recordOffset + REC_HEADER_SIZE
            for (i in 0 until height) {
                val raw = data.getInt16(elevStart + i * 2, littleEndian = false).toInt()
                val y = height - 1 - i // flip south→north (file) to north→south (WW)
                out[y * width + columnIndex] = if (raw != NODATA_VALUE && raw in MIN_VALUE..MAX_VALUE) {
                    raw.toShort()
                } else {
                    Short.MIN_VALUE
                }
            }
        }

        /** Linear scan of the decoded grid for non-null min/max. Returns `(0.0, 0.0)`
         *  for a grid with no valid posts. */
        internal fun computeRange(elevations: ShortArray): Pair<Double, Double> {
            var min = Double.MAX_VALUE
            var max = -Double.MAX_VALUE
            for (v in elevations) {
                if (v != Short.MIN_VALUE) {
                    val d = v.toDouble()
                    if (d < min) min = d
                    if (d > max) max = d
                }
            }
            return if (min == Double.MAX_VALUE) 0.0 to 0.0 else min to max
        }

        private fun checkRecordId(data: BinaryDataView, offset: Int, expected: String) {
            val id = readAscii(data, offset, expected.length)
            if (!id.equals(expected, ignoreCase = true)) error(
                logMessage(ERROR, "DTED", "checkRecordId", "Bad file format: expected $expected, got '$id'")
            )
        }

        private fun readAscii(data: BinaryDataView, offset: Int, length: Int): String {
            val chars = CharArray(length)
            for (i in 0 until length) chars[i] = data.getUint8(offset + i).toChar()
            return chars.concatToString()
        }

        private fun readAngle(raw: String): Angle? {
            // DTED packs angles as DDDMMSSH / DDMMSSH (optionally with .S fractional
            // seconds, which the spec doesn't actually use). Slice into D/M/S parts and
            // hand "DD MM SS H" to the standard parser.
            val s = raw.trim()
            val degLen = when (s.length) {
                7, 9 -> 2 // DDMMSSH or DDMMSS.SH
                8, 10 -> 3 // DDDMMSSH or DDDMMSS.SH
                else -> return null
            }
            val degrees = s.substring(0, degLen)
            val minutes = s.substring(degLen, degLen + 2)
            val seconds = s.substring(degLen + 2, degLen + 4)
            val hemi = s.last()
            return Angle.fromDMS("$degrees $minutes $seconds $hemi")
        }

        private fun readLevel(raw: String): Int? {
            val s = raw.trim()
            if (!s.startsWith("DTED") || s.length != 5) return null
            return s[4].digitToIntOrNull()
        }

        private fun readClassLevel(raw: String): String? = when (raw.trim().uppercase()) {
            "U" -> "Unclassified"
            "R" -> "Restricted"
            "C" -> "Confidential"
            "S" -> "Secret"
            else -> null
        }
    }
}
