package earth.worldwind.formats.nitf

import earth.worldwind.formats.BinaryDataView

/**
 * Cursor-style reader over a [BinaryDataView] holding a NITF/NSIF byte stream.
 * NITF fields are fixed-width ASCII (BCS-A or BCS-N per MIL-STD-2500C §5.1.1) and
 * the spec defines almost the entire format as a strict positional walk, so a
 * moving-cursor abstraction matches the grammar 1:1.
 *
 * String fields are space-padded; numeric fields are zero-padded. Spec-allowed
 * blank-padded numerics ("    " for "not present") yield `null` from [readIntOrNull]
 * / [readLongOrNull] and `0` from [readInt] / [readLong] — callers needing to
 * distinguish "blank" from "zero" must use the nullable variants.
 *
 * A small set of binary primitives ([readUByte], [readUShort], [readUInt],
 * [readBytes]) is provided for the rare binary fields (FBKGC RGB, image data,
 * mask records).
 */
internal class NitfReader(val view: BinaryDataView, var position: Int = 0) {

    val byteLength: Int get() = view.byteLength
    val remaining: Int get() = byteLength - position

    fun skip(n: Int) { position += n }
    fun seek(offset: Int) { position = offset }

    fun readBytes(n: Int): ByteArray {
        val bytes = ByteArray(n)
        val src = view.asByteArray()
        src.copyInto(bytes, 0, position, position + n)
        position += n
        return bytes
    }

    fun readString(n: Int): String {
        val bytes = readBytes(n)
        // BCS-A is a strict subset of ASCII; decoding as ISO-8859-1 / Latin-1 is
        // byte-equivalent without pulling a charset table into common code.
        val sb = StringBuilder(n)
        for (b in bytes) sb.append((b.toInt() and 0xFF).toChar())
        return sb.toString().trimEnd(' ')
    }

    /**
     * Read [n] BCS-A characters, trimming surrounding spaces. Returns `null`
     * when the trimmed value is empty — useful for optional fields that the
     * spec leaves blank-padded.
     */
    fun readStringOrNull(n: Int): String? {
        val s = readString(n)
        return s.ifEmpty { null }
    }

    /** Numeric field. Blank-padded yields 0; otherwise parsed strictly. */
    fun readInt(n: Int): Int {
        val s = readString(n).trim()
        return if (s.isEmpty()) 0 else s.toInt()
    }

    fun readIntOrNull(n: Int): Int? {
        val s = readString(n).trim()
        return if (s.isEmpty()) null else s.toInt()
    }

    fun readLong(n: Int): Long {
        val s = readString(n).trim()
        return if (s.isEmpty()) 0L else s.toLong()
    }

    fun readLongOrNull(n: Int): Long? {
        val s = readString(n).trim()
        return if (s.isEmpty()) null else s.toLong()
    }

    fun readDouble(n: Int): Double {
        val s = readString(n).trim()
        return if (s.isEmpty()) 0.0 else s.toDouble()
    }

    fun readUByte(): Int {
        val v = view.getUint8(position)
        position += 1
        return v
    }

    fun readUShort(): Int {
        val v = view.getUint16(position, littleEndian = false)
        position += 2
        return v
    }

    fun readUInt(): Long {
        val v = view.getUint32(position, littleEndian = false)
        position += 4
        return v
    }

    /**
     * Read the next [bitCount] bits as an unsigned integer, MSB-first, starting
     * at the current byte boundary. Used for mask subsection / RPF-style packed
     * fields where the bit width is spec-defined per file (TPXCD up to 32 bits).
     * Advances [position] by `ceil(bitCount / 8)` bytes.
     */
    fun readBitsAsLong(bitCount: Int): Long {
        require(bitCount in 0..64) { "bitCount $bitCount out of range" }
        if (bitCount == 0) return 0L
        val byteCount = (bitCount + 7) / 8
        var result = 0L
        for (i in 0 until byteCount) {
            result = (result shl 8) or view.getUint8(position + i).toLong()
        }
        position += byteCount
        val pad = byteCount * 8 - bitCount
        return if (pad > 0) result ushr pad else result
    }
}
