package earth.worldwind.formats.ogc3d

import earth.worldwind.formats.BinaryDataView

/**
 * Common header shared by every binary 3D Tiles container (b3dm / i3dm / pnts). 28 bytes:
 *
 * ```
 * Bytes 0..3   : magic (ASCII "b3dm" / "i3dm" / "pnts")
 * Bytes 4..7   : version (uint32 LE) = 1
 * Bytes 8..11  : byteLength (uint32 LE) — total payload length including header
 * Bytes 12..15 : featureTableJSONByteLength (uint32 LE)
 * Bytes 16..19 : featureTableBinaryByteLength (uint32 LE)
 * Bytes 20..23 : batchTableJSONByteLength (uint32 LE)
 * Bytes 24..27 : batchTableBinaryByteLength (uint32 LE)
 * ```
 *
 * After the header come (in order): feature-table JSON, feature-table binary, batch-table
 * JSON, batch-table binary. JSON sections are space-padded to 8-byte boundaries per spec.
 * The format-specific payload (glTF for b3dm/i3dm, points binary for pnts) starts at the
 * end of the batch-table binary.
 *
 * Note: [cmpt] is the only container that has a *different* header layout — see
 * [CmptHeader].
 */
class Ogc3dHeader internal constructor(
    /** ASCII magic identifier — `b3dm`, `i3dm`, or `pnts`. */
    val magic: String,
    val version: Int,
    val byteLength: Int,
    val featureTableJsonByteLength: Int,
    val featureTableBinaryByteLength: Int,
    val batchTableJsonByteLength: Int,
    val batchTableBinaryByteLength: Int,
    /** Offset (from the start of the input) where the format-specific payload begins. */
    val payloadOffset: Int,
) {
    val featureTableJsonOffset: Int get() = HEADER_SIZE
    val featureTableBinaryOffset: Int get() = featureTableJsonOffset + featureTableJsonByteLength
    val batchTableJsonOffset: Int get() = featureTableBinaryOffset + featureTableBinaryByteLength
    val batchTableBinaryOffset: Int get() = batchTableJsonOffset + batchTableJsonByteLength

    companion object {
        const val HEADER_SIZE = 28

        /**
         * Parse the header. Throws [IllegalArgumentException] when the payload is shorter
         * than 28 bytes or the magic / version / sub-lengths fail spec validation.
         */
        fun parse(bytes: ByteArray, offset: Int = 0): Ogc3dHeader {
            require(bytes.size - offset >= HEADER_SIZE) {
                "3D Tiles header truncated: have ${bytes.size - offset} bytes, need $HEADER_SIZE"
            }
            val view = BinaryDataView(bytes)
            val magic = bytes.decodeToString(offset, offset + 4)
            require(magic == "b3dm" || magic == "i3dm" || magic == "pnts") {
                "3D Tiles magic mismatch: expected b3dm/i3dm/pnts, got '$magic'"
            }
            val version = view.getInt32(offset + 4, littleEndian = true)
            require(version == 1) { "3D Tiles version mismatch: expected 1, got $version" }
            val byteLength = view.getInt32(offset + 8, littleEndian = true)
            val ftJson = view.getInt32(offset + 12, littleEndian = true)
            val ftBin = view.getInt32(offset + 16, littleEndian = true)
            val btJson = view.getInt32(offset + 20, littleEndian = true)
            val btBin = view.getInt32(offset + 24, littleEndian = true)
            val payloadOffset = HEADER_SIZE + ftJson + ftBin + btJson + btBin
            require(payloadOffset <= byteLength) {
                "3D Tiles header sub-lengths overflow byteLength: " +
                    "header sum=$payloadOffset, byteLength=$byteLength"
            }
            return Ogc3dHeader(
                magic = magic,
                version = version,
                byteLength = byteLength,
                featureTableJsonByteLength = ftJson,
                featureTableBinaryByteLength = ftBin,
                batchTableJsonByteLength = btJson,
                batchTableBinaryByteLength = btBin,
                payloadOffset = offset + payloadOffset,
            )
        }
    }
}

/**
 * Composite (`cmpt`) header — 16 bytes, no feature/batch tables. The payload is a
 * concatenation of [tilesLength] inner tile binaries, each itself a b3dm/i3dm/pnts/cmpt.
 *
 * ```
 * Bytes 0..3   : magic ("cmpt")
 * Bytes 4..7   : version (uint32 LE) = 1
 * Bytes 8..11  : byteLength (uint32 LE)
 * Bytes 12..15 : tilesLength (uint32 LE)
 * ```
 */
class CmptHeader internal constructor(
    val version: Int,
    val byteLength: Int,
    val tilesLength: Int,
) {
    companion object {
        const val HEADER_SIZE = 16

        fun parse(bytes: ByteArray, offset: Int = 0): CmptHeader {
            require(bytes.size - offset >= HEADER_SIZE) {
                "cmpt header truncated: have ${bytes.size - offset} bytes, need $HEADER_SIZE"
            }
            val view = BinaryDataView(bytes)
            val magic = bytes.decodeToString(offset, offset + 4)
            require(magic == "cmpt") { "cmpt magic mismatch: got '$magic'" }
            val version = view.getInt32(offset + 4, littleEndian = true)
            require(version == 1) { "cmpt version mismatch: expected 1, got $version" }
            val byteLength = view.getInt32(offset + 8, littleEndian = true)
            val tilesLength = view.getInt32(offset + 12, littleEndian = true)
            return CmptHeader(version, byteLength, tilesLength)
        }
    }
}
