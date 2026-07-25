package earth.worldwind.formats.i3s

/**
 * Minimal reader for the Draco container header + metadata section, enough to support I3S
 * `compressedAttributes` buffers without native-decoder changes: sniff the `DRACO` magic and pull
 * the per-node position quantization scales Esri stores as attribute metadata (`i3s-scale_x` /
 * `i3s-scale_y`, degrees per stored unit — global scenes pre-scale lon/lat offsets to metres so
 * quantization is isotropic). The mesh itself is decoded by the registered platform decoder; this
 * parser never touches the compressed payload, only the plain-varint metadata block that precedes it.
 */
internal object I3sDracoMeta {
    /** True when [bytes] is a Draco stream (5-byte `DRACO` magic + version header). */
    fun isDraco(bytes: ByteArray): Boolean = bytes.size >= HEADER_SIZE &&
        bytes[0] == 'D'.code.toByte() && bytes[1] == 'R'.code.toByte() && bytes[2] == 'A'.code.toByte() &&
        bytes[3] == 'C'.code.toByte() && bytes[4] == 'O'.code.toByte()

    /** `[scaleX, scaleY]` from `i3s-scale_x`/`i3s-scale_y` attribute metadata, or null when absent
     *  (projected-CRS packages store positions unscaled). Null on any malformed metadata. */
    fun positionScales(bytes: ByteArray): DoubleArray? = try {
        parseScales(bytes)
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun parseScales(bytes: ByteArray): DoubleArray? {
        if (!isDraco(bytes)) return null
        // Header: magic(5) verMajor(1) verMinor(1) encoderType(1) method(1) flags(2 LE).
        val flags = (bytes[9].toInt() and 0xFF) or ((bytes[10].toInt() and 0xFF) shl 8)
        if (flags and METADATA_FLAG == 0) return null
        val reader = Reader(bytes, HEADER_SIZE)
        var scaleX = Double.NaN
        var scaleY = Double.NaN
        val attributeCount = reader.varint()
        if (attributeCount > MAX_COUNT) return null
        repeat(attributeCount) {
            reader.varint() // attribute unique id
            reader.element { key, valueOffset, valueSize ->
                if (valueSize == 8) when (key) {
                    "i3s-scale_x" -> scaleX = reader.doubleAt(valueOffset)
                    "i3s-scale_y" -> scaleY = reader.doubleAt(valueOffset)
                }
            }
        }
        return if (scaleX.isNaN() || scaleY.isNaN()) null else doubleArrayOf(scaleX, scaleY)
    }

    /** Sequential varint/entry reader over the metadata block. Bounds-checks every read explicitly —
     *  Kotlin/JS array indexing doesn't trap out-of-range, so truncated input must fail here. */
    private class Reader(private val bytes: ByteArray, private var pos: Int) {
        private fun next(): Int {
            if (pos >= bytes.size) throw IndexOutOfBoundsException("truncated metadata at $pos")
            return bytes[pos++].toInt()
        }

        fun varint(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val b = next()
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
                if (shift > 35) throw IndexOutOfBoundsException("varint too long")
            }
        }

        fun doubleAt(offset: Int): Double {
            var bits = 0L
            for (i in 7 downTo 0) bits = (bits shl 8) or (bytes[offset + i].toLong() and 0xFF)
            return Double.fromBits(bits)
        }

        /** One metadata element: entries (u8-len key, u8-len value), then named sub-elements. */
        fun element(onEntry: (key: String, valueOffset: Int, valueSize: Int) -> Unit) {
            val entries = varint()
            if (entries > MAX_COUNT) throw IndexOutOfBoundsException("metadata entry count $entries")
            repeat(entries) {
                val key = string()
                val valueSize = next() and 0xFF
                if (pos + valueSize > bytes.size) throw IndexOutOfBoundsException("metadata value")
                onEntry(key, pos, valueSize)
                pos += valueSize
            }
            val subs = varint()
            if (subs > MAX_COUNT) throw IndexOutOfBoundsException("metadata sub count $subs")
            repeat(subs) {
                string() // sub-element name
                element(onEntry)
            }
        }

        private fun string(): String {
            val size = next() and 0xFF
            if (pos + size > bytes.size) throw IndexOutOfBoundsException("metadata string")
            return bytes.decodeToString(pos, pos + size).also { pos += size }
        }
    }

    private const val HEADER_SIZE = 11
    private const val METADATA_FLAG = 0x8000

    /** Sanity cap on metadata counts — corrupt varints must not drive long loops. */
    private const val MAX_COUNT = 4096
}
