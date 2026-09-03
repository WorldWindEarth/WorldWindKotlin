package earth.worldwind.formats

/**
 * Pure-Kotlin DEFLATE (RFC 1951) / zlib (RFC 1950) decompressor. The engine has no
 * cross-platform inflater — `java.util.zip` is JVM-only and the SPZ hook
 * ([earth.worldwind.layer.ogc3d.content.spz.SpzInflater]) is a gzip-shaped, app-installed
 * seam — so TIFF `Deflate` / `AdobeDeflate` blocks (GDAL's default DEM compression) get
 * their own decoder here, compiled for every KMP target.
 *
 * Canonical-Huffman decode follows the `puff.c` reference: symbols are ordered by code
 * length, so a code is located by counting how many codes of each length precede it
 * rather than by building a lookup table.
 */
internal object Inflate {
    /** Extra bits + base value for length codes 257..285. */
    private val LENGTH_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258
    )
    private val LENGTH_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0
    )
    /** Extra bits + base value for distance codes 0..29. */
    private val DIST_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577
    )
    private val DIST_EXTRA = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13
    )
    /** Order in which code-length-code lengths appear in a dynamic block header. */
    private val CLEN_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    /** Canonical Huffman table: [count] holds how many codes exist per bit length, [symbol] the
     *  symbols ordered by (length, symbol). */
    private class Huffman(val count: IntArray, val symbol: IntArray)

    private fun buildHuffman(lengths: IntArray, n: Int): Huffman {
        val count = IntArray(16)
        for (i in 0 until n) count[lengths[i]]++
        count[0] = 0
        val offs = IntArray(16)
        for (len in 1..15) offs[len] = offs[len - 1] + count[len - 1]
        val symbol = IntArray(n)
        for (i in 0 until n) if (lengths[i] != 0) symbol[offs[lengths[i]]++] = i
        return Huffman(count, symbol)
    }

    /** Bit-at-a-time reader; DEFLATE packs codes LSB-first within each byte. */
    private class BitReader(val data: ByteArray, var pos: Int, val end: Int) {
        private var bitBuf = 0
        private var bitCnt = 0

        fun bits(need: Int): Int {
            while (bitCnt < need) {
                val b = if (pos < end) data[pos++].toInt() and 0xFF else 0
                bitBuf = bitBuf or (b shl bitCnt)
                bitCnt += 8
            }
            val value = bitBuf and ((1 shl need) - 1)
            bitBuf = bitBuf ushr need
            bitCnt -= need
            return value
        }

        /** Drop the partially consumed byte — stored blocks resume on a byte boundary. */
        fun alignToByte() {
            bitBuf = 0
            bitCnt = 0
        }

        fun decode(h: Huffman): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..15) {
                code = code or bits(1)
                val count = h.count[len]
                if (code - first < count) return h.symbol[index + (code - first)]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            error("Invalid Huffman code in DEFLATE stream")
        }
    }

    private val FIXED_LITERALS by lazy {
        val lengths = IntArray(288)
        for (i in 0 until 144) lengths[i] = 8
        for (i in 144 until 256) lengths[i] = 9
        for (i in 256 until 280) lengths[i] = 7
        for (i in 280 until 288) lengths[i] = 8
        buildHuffman(lengths, 288)
    }
    private val FIXED_DISTANCES by lazy { buildHuffman(IntArray(30) { 5 }, 30) }

    /**
     * Inflate a zlib stream (2-byte header + deflate data + Adler-32) or a bare deflate
     * stream — the two are told apart by the zlib header check bits, so callers don't have
     * to know which one a TIFF encoder emitted.
     *
     * [expectedSize] is the caller's known uncompressed size (a TIFF block's
     * `rows * width * bytesPerPixel`); output is truncated / padded to it so a corrupt
     * block can't hand back a short array the sample walker would run off.
     */
    fun inflate(src: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray {
        var start = offset
        // zlib header: CMF/FLG, CM must be 8 (deflate) and (CMF<<8 | FLG) a multiple of 31.
        if (length >= 2) {
            val cmf = src[offset].toInt() and 0xFF
            val flg = src[offset + 1].toInt() and 0xFF
            if (cmf and 0x0F == 8 && (cmf shl 8 or flg) % 31 == 0) start = offset + 2
        }
        // A block that decodes short keeps the rows that did decode and leaves zeros in the
        // tail — better than discarding a partially readable block, and the sample walker
        // stays in bounds either way.
        return ByteArray(expectedSize).also { inflateRaw(src, start, offset + length, it) }
    }

    /** Raw DEFLATE into [out]; returns the number of bytes written (never more than out.size). */
    private fun inflateRaw(src: ByteArray, from: Int, to: Int, out: ByteArray): Int {
        val br = BitReader(src, from, to)
        var outPos = 0
        val lengths = IntArray(320)
        while (true) {
            val isFinal = br.bits(1)
            when (val type = br.bits(2)) {
                0 -> { // stored
                    br.alignToByte()
                    if (br.pos + 4 > to) return outPos
                    val len = (src[br.pos].toInt() and 0xFF) or ((src[br.pos + 1].toInt() and 0xFF) shl 8)
                    br.pos += 4 // LEN + NLEN
                    val n = minOf(len, to - br.pos, out.size - outPos)
                    src.copyInto(out, outPos, br.pos, br.pos + n)
                    br.pos += len
                    outPos += n
                }
                1, 2 -> {
                    val literals: Huffman
                    val distances: Huffman
                    if (type == 1) {
                        literals = FIXED_LITERALS
                        distances = FIXED_DISTANCES
                    } else {
                        val hlit = br.bits(5) + 257
                        val hdist = br.bits(5) + 1
                        val hclen = br.bits(4) + 4
                        val clen = IntArray(19)
                        for (i in 0 until hclen) clen[CLEN_ORDER[i]] = br.bits(3)
                        val clenTable = buildHuffman(clen, 19)
                        var i = 0
                        while (i < hlit + hdist) {
                            when (val sym = br.decode(clenTable)) {
                                16 -> { // repeat previous length 3..6 times
                                    val prev = if (i > 0) lengths[i - 1] else 0
                                    var repeat = 3 + br.bits(2)
                                    while (repeat-- > 0 && i < hlit + hdist) lengths[i++] = prev
                                }
                                17 -> { // repeat zero 3..10 times
                                    var repeat = 3 + br.bits(3)
                                    while (repeat-- > 0 && i < hlit + hdist) lengths[i++] = 0
                                }
                                18 -> { // repeat zero 11..138 times
                                    var repeat = 11 + br.bits(7)
                                    while (repeat-- > 0 && i < hlit + hdist) lengths[i++] = 0
                                }
                                else -> lengths[i++] = sym
                            }
                        }
                        literals = buildHuffman(lengths, hlit)
                        val distLengths = IntArray(hdist)
                        lengths.copyInto(distLengths, 0, hlit, hlit + hdist)
                        distances = buildHuffman(distLengths, hdist)
                    }
                    while (true) {
                        val sym = br.decode(literals)
                        if (sym < 256) {
                            if (outPos < out.size) out[outPos++] = sym.toByte() else return outPos
                        } else if (sym == 256) {
                            break // end of block
                        } else {
                            val lenIdx = sym - 257
                            if (lenIdx >= LENGTH_BASE.size) return outPos
                            val len = LENGTH_BASE[lenIdx] + br.bits(LENGTH_EXTRA[lenIdx])
                            val distSym = br.decode(distances)
                            if (distSym >= DIST_BASE.size) return outPos
                            val dist = DIST_BASE[distSym] + br.bits(DIST_EXTRA[distSym])
                            if (dist > outPos) return outPos // malformed back-reference
                            var copied = 0
                            var from2 = outPos - dist
                            while (copied < len && outPos < out.size) {
                                out[outPos++] = out[from2++]
                                copied++
                            }
                            if (outPos == out.size && copied < len) return outPos
                        }
                        if (br.pos >= to && outPos >= out.size) return outPos
                    }
                }
                else -> return outPos // reserved block type — malformed
            }
            if (isFinal == 1 || outPos >= out.size) return outPos
            if (br.pos >= to) return outPos
        }
    }
}
