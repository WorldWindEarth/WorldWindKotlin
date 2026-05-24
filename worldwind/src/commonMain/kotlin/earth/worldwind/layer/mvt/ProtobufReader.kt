package earth.worldwind.layer.mvt

/**
 * Minimal proto3 wire-format reader, sized exactly for the [vector_tile.proto](
 * https://github.com/mapbox/vector-tile-spec/blob/master/2.1/vector_tile.proto) subset:
 * varints (wire type 0), 32/64-bit fixed (1/5), and length-delimited bytes/strings/sub-messages
 * (2). Unknown fields are skipped via [skipValue].
 *
 * A pbandk dependency would pull in a Kotlin protobuf codegen plugin for ~3 fields' worth of
 * decode; hand-rolling stays a single source file with no build wiring.
 *
 * Reader is stateful — [pos] is the cursor into [data]. To parse a sub-message use
 * [readDelimitedLimit] to get its end offset, loop while [pos] < that limit, and dispatch each
 * tag yourself.
 */
internal class ProtobufReader(private val data: ByteArray) {
    /** Current byte offset into [data]. Public so sub-message parsers can compare against limits. */
    var pos: Int = 0

    /** Decode the next varint into the full 64-bit range. Throws on truncation or > 10-byte overflow. */
    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= data.size) error("MVT: truncated varint at $pos")
            val b = data[pos++].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift >= 64) error("MVT: varint overflow at $pos")
        }
    }

    fun readInt(): Int = readVarint().toInt()
    fun readLong(): Long = readVarint()
    fun readBool(): Boolean = readVarint() != 0L

    /** ZigZag-decoded signed varint (proto sint32/sint64). */
    fun readSInt(): Long {
        val raw = readVarint()
        return (raw ushr 1) xor -(raw and 1L)
    }

    fun readFloat(): Float {
        if (pos + 4 > data.size) error("MVT: truncated I32 at $pos")
        val bits =
            (data[pos].toInt() and 0xff) or
            ((data[pos + 1].toInt() and 0xff) shl 8) or
            ((data[pos + 2].toInt() and 0xff) shl 16) or
            ((data[pos + 3].toInt() and 0xff) shl 24)
        pos += 4
        return Float.fromBits(bits)
    }

    fun readDouble(): Double {
        if (pos + 8 > data.size) error("MVT: truncated I64 at $pos")
        var bits = 0L
        for (i in 0..7) bits = bits or ((data[pos + i].toLong() and 0xff) shl (i * 8))
        pos += 8
        return Double.fromBits(bits)
    }

    fun readString(): String {
        val len = readVarint().toInt()
        val s = data.decodeToString(pos, pos + len)
        pos += len
        return s
    }

    /**
     * Begin a length-delimited region (sub-message, packed-repeated). Returns the absolute end
     * offset; the caller iterates `while (reader.pos < limit) { ... }` and dispatches each tag.
     */
    fun readDelimitedLimit(): Int {
        val len = readVarint().toInt()
        return pos + len
    }

    /** Skip the value of a field whose tag (`(field << 3) | wireType`) was just read. */
    fun skipValue(tag: Int) {
        when (tag and 0x7) {
            0 -> readVarint()
            1 -> pos += 8
            2 -> { val n = readVarint().toInt(); pos += n }
            5 -> pos += 4
            else -> error("MVT: unknown wire type ${tag and 0x7} for tag $tag at $pos")
        }
    }
}
