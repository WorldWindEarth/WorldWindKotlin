package earth.worldwind.formats.ogc3d

import earth.worldwind.formats.BinaryDataView

/**
 * One sub-payload extracted from a Composite container — the magic plus the raw bytes of
 * that sub-payload. Callers route by [magic] to the right loader (b3dm / i3dm / pnts /
 * cmpt; nested cmpt is allowed by the spec and re-enters [CmptLoader.parse]).
 */
class CmptInnerTile internal constructor(
    val magic: String,
    val bytes: ByteArray,
)

/**
 * Splits a Composite (cmpt) container into its inner tiles. The format is:
 *
 * ```
 * 16-byte CmptHeader
 * for i in 0..tilesLength:
 *   inner tile (its own b3dm / i3dm / pnts / cmpt with its own header)
 * ```
 *
 * Each inner tile's `byteLength` field (at offset +8 from the inner tile's start) drives
 * how many bytes to slice for that sub-payload.
 */
object CmptLoader {
    fun parse(bytes: ByteArray): List<CmptInnerTile> {
        val header = CmptHeader.parse(bytes)
        val view = BinaryDataView(bytes)
        val out = ArrayList<CmptInnerTile>(header.tilesLength)
        var cursor = CmptHeader.HEADER_SIZE
        for (i in 0 until header.tilesLength) {
            require(cursor + 12 <= bytes.size) {
                "cmpt inner tile $i header truncated at offset $cursor"
            }
            val innerMagic = bytes.decodeToString(cursor, cursor + 4)
            val innerByteLength = view.getInt32(cursor + 8, littleEndian = true)
            require(innerByteLength in 12..(bytes.size - cursor)) {
                "cmpt inner tile $i byteLength $innerByteLength out of range " +
                    "(remaining ${bytes.size - cursor})"
            }
            out.add(CmptInnerTile(magic = innerMagic, bytes = bytes.sliceArray(cursor until cursor + innerByteLength)))
            cursor += innerByteLength
        }
        return out
    }
}
