package earth.worldwind.formats.geotiff

import earth.worldwind.formats.BinaryDataView

/**
 * TIFF Adobe-style LZW decoder. Implements the variant the TIFF 6 spec describes (a
 * straight LZW with code widths 9 → 12 bits, big-endian-packed bitstream, MSB-first
 * within each code, dictionary cleared on `CLEAR_CODE = 256`, stream terminator
 * `EOI_CODE = 257`). Adobe's TIFF dialect rolls the code-width bump UP one bit early
 * compared to the canonical LZW spec - widths advance to 10/11/12 when the next code
 * to be added would EQUAL `2^width`, not exceed it. That `width - 1` quirk is the
 * single most common reason naïve LZW implementations decode garbage from real TIFFs;
 * mil.nga.tiff's reference and libtiff both honour it.
 *
 * Used by [GeoTiffReader] to decode strips/tiles whose `Compression` tag is
 * [TiffConstants.Compression.LZW] (5). Result is the same byte sequence the
 * uncompressed path consumes - the caller still applies horizontal-differencing
 * predictor (TIFF tag 317) and sample-format unpacking.
 */
internal object LzwDecoder {

    private const val CLEAR_CODE = 256
    private const val EOI_CODE = 257
    private const val MIN_CODE_WIDTH = 9
    private const val MAX_CODE_WIDTH = 12
    /** Highest dictionary index addressable at 12-bit width. */
    private const val MAX_DICT_SIZE = 1 shl MAX_CODE_WIDTH

    /**
     * Decode [length] bytes of LZW-compressed input starting at [offset] in [src].
     * The output's exact size isn't known up front; the caller passes [outCapacity]
     * (the uncompressed strip/tile byte count from the TIFF header) so we can size
     * the result without a second scan.
     */
    fun decode(src: BinaryDataView, offset: Int, length: Int, outCapacity: Int): ByteArray {
        val out = ByteArray(outCapacity)
        var outPos = 0

        // Bitstream cursor: `bitOffset` is in bits from `offset`. Adobe TIFF LZW packs
        // codes MSB-first regardless of host endianness.
        var bitOffset = 0
        val totalBits = length * 8

        // Dictionary entries are stored as parallel arrays of (prefixIndex, lastByte).
        // Index 0..255 are byte literals (prefix = -1, lastByte = i); CLEAR/EOI sit at
        // 256/257; new strings start at 258.
        val prefix = IntArray(MAX_DICT_SIZE)
        val suffix = ByteArray(MAX_DICT_SIZE)
        // String length cache lets us emit a code's bytes without recursion (would
        // overflow on long matches).
        val length_ = IntArray(MAX_DICT_SIZE)
        for (i in 0 until 256) {
            prefix[i] = -1
            suffix[i] = i.toByte()
            length_[i] = 1
        }

        var codeWidth = MIN_CODE_WIDTH
        var nextCode = 258
        var prev = -1
        // Reusable scratch for emit; sized to MAX_DICT_SIZE = 4096 chars (one full match).
        val emitBuf = ByteArray(MAX_DICT_SIZE)

        while (bitOffset + codeWidth <= totalBits) {
            val code = readBits(src, offset, bitOffset, codeWidth)
            bitOffset += codeWidth

            if (code == EOI_CODE) break
            if (code == CLEAR_CODE) {
                codeWidth = MIN_CODE_WIDTH
                nextCode = 258
                prev = -1
                continue
            }

            // Decode the code into the dictionary's longest matching string. Two cases:
            //  1) `code < nextCode`: code is already in dict, walk back the prefix chain.
            //  2) `code == nextCode`: KwKwK case - prev's string + first byte of itself.
            val emitLen: Int
            val firstByte: Byte
            if (code < nextCode) {
                emitLen = length_[code]
                firstByte = walkChain(code, prefix, suffix, emitBuf, emitLen)
            } else if (prev >= 0) {
                val prevLen = length_[prev]
                val prevFirst = walkChain(prev, prefix, suffix, emitBuf, prevLen)
                emitBuf[prevLen] = prevFirst
                emitLen = prevLen + 1
                firstByte = prevFirst
            } else {
                // Malformed: code without a CLEAR or prev. Bail rather than corrupt.
                break
            }

            // Emit this code's bytes. Truncate at outCapacity rather than expand the
            // buffer - elevation tiles are size-known up front; truncation here means
            // the source declared a smaller strip than it produced.
            val toCopy = minOf(emitLen, outCapacity - outPos)
            for (k in 0 until toCopy) out[outPos + k] = emitBuf[k]
            outPos += toCopy
            if (outPos >= outCapacity) break

            // Add (prev + firstByte) to dictionary unless the table is full. When `prev`
            // is the sentinel (post-CLEAR / first code) we don't add - the next code's
            // prefix will be `prev` set below.
            if (prev >= 0 && nextCode < MAX_DICT_SIZE) {
                prefix[nextCode] = prev
                suffix[nextCode] = firstByte
                length_[nextCode] = length_[prev] + 1
                nextCode++
                // Adobe TIFF LZW widens the code BEFORE writing the code that hits the
                // boundary - i.e. the next code from the encoder needs `width + 1` bits.
                // Equivalent: bump width when `nextCode + 1 == 1 shl width`.
                if (nextCode + 1 == (1 shl codeWidth) && codeWidth < MAX_CODE_WIDTH) {
                    codeWidth++
                }
            }
            prev = code
        }
        // Trim the result to actually-decoded length when the source ran out before
        // filling outCapacity (very rare for well-formed TIFFs).
        return if (outPos == outCapacity) out else out.copyOf(outPos)
    }

    /** Read [width] bits (MSB-first within a byte, big-endian byte order in the
     *  bitstream) starting at bit [bitOffset] from byte [byteBase] in [src]. */
    private fun readBits(src: BinaryDataView, byteBase: Int, bitOffset: Int, width: Int): Int {
        var bitsLeft = width
        var byteIdx = byteBase + (bitOffset ushr 3)
        var bitInByte = bitOffset and 7
        var result = 0
        while (bitsLeft > 0) {
            val available = 8 - bitInByte
            val take = if (bitsLeft < available) bitsLeft else available
            val byte = src.getUint8(byteIdx)
            // Extract the high `take` bits starting at `bitInByte` from this byte.
            val shifted = (byte shr (available - take)) and ((1 shl take) - 1)
            result = (result shl take) or shifted
            bitsLeft -= take
            bitInByte += take
            if (bitInByte >= 8) { bitInByte = 0; byteIdx++ }
        }
        return result
    }

    /** Write the dictionary string at [code] into [out] (rightmost byte first then
     *  reversed in place) and return the first byte of the string. */
    private fun walkChain(
        code: Int, prefix: IntArray, suffix: ByteArray, out: ByteArray, len: Int
    ): Byte {
        var c = code
        var i = len - 1
        while (c >= 0 && i >= 0) {
            out[i] = suffix[c]
            c = prefix[c]
            i--
        }
        return out[0]
    }
}
