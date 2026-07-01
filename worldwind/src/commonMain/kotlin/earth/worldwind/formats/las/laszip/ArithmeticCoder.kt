package earth.worldwind.formats.las.laszip

/**
 * Faithful Kotlin port of LASzip's adapted Amir Said FastAC arithmetic decoder and its two
 * probability models (`arithmeticdecoder.cpp` / `arithmeticmodel.cpp`, Apache-2.0, rapidlasso
 * GmbH). All 32-bit arithmetic uses [UInt] so the unsigned compares, wrapping multiplies, and
 * divisions match the C `U32` semantics exactly — a single deviation produces garbage output.
 */

internal const val AC_MIN_LENGTH = 0x01000000u
internal const val AC_MAX_LENGTH = 0xFFFFFFFFu
internal const val BM_LENGTH_SHIFT = 13
internal const val BM_MAX_COUNT = 1 shl BM_LENGTH_SHIFT
internal const val DM_LENGTH_SHIFT = 15
internal const val DM_MAX_COUNT = 1 shl DM_LENGTH_SHIFT

/** Sequential byte reader with seek, over an in-memory buffer. Reads past the end yield 0 —
 *  the chunked decoder never relies on those bytes (point counts bound every chunk). */
internal class ByteStreamIn(private val bytes: ByteArray) {
    var pos = 0
    fun getByte(): UInt = if (pos < bytes.size) (bytes[pos++].toInt() and 0xFF).toUInt() else 0u.also { pos++ }
    fun seek(p: Int) { pos = p }
    fun getInt32LE(): Int {
        val b0 = getByte(); val b1 = getByte(); val b2 = getByte(); val b3 = getByte()
        return (b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)).toInt()
    }
    fun getInt64LE(): Long {
        val lo = getInt32LE().toLong() and 0xFFFFFFFFL
        val hi = getInt32LE().toLong() and 0xFFFFFFFFL
        return (hi shl 32) or lo
    }
}

/** Adaptive binary model (`ArithmeticBitModel`). */
internal class ArithmeticBitModel {
    var bit0Prob = 0u
    var bit0Count = 0
    var bitCount = 0
    private var updateCycle = 0
    var bitsUntilUpdate = 0

    init { init() }

    fun init() {
        bit0Count = 1
        bitCount = 2
        bit0Prob = 1u shl (BM_LENGTH_SHIFT - 1)
        updateCycle = 4
        bitsUntilUpdate = 4
    }

    fun update() {
        bitCount += updateCycle
        if (bitCount > BM_MAX_COUNT) {
            bitCount = (bitCount + 1) shr 1
            bit0Count = (bit0Count + 1) shr 1
            if (bit0Count == bitCount) bitCount++
        }
        val scale = 0x80000000u / bitCount.toUInt()
        bit0Prob = (bit0Count.toUInt() * scale) shr (31 - BM_LENGTH_SHIFT)
        updateCycle = (5 * updateCycle) shr 2
        if (updateCycle > 64) updateCycle = 64
        bitsUntilUpdate = updateCycle
    }
}

/** Adaptive general model (`ArithmeticModel`), decoder side only (compress == false). */
internal class ArithmeticModel(val symbols: Int) {
    lateinit var distribution: IntArray
    lateinit var symbolCount: IntArray
    var decoderTable: IntArray? = null
    var lastSymbol = 0
    var tableSize = 0
    var tableShift = 0
    private var totalCount = 0
    private var updateCycle = 0
    var symbolsUntilUpdate = 0

    fun init(table: IntArray? = null) {
        lastSymbol = symbols - 1
        if (symbols > 16) {
            var tableBits = 3
            while (symbols > (1 shl (tableBits + 2))) tableBits++
            tableSize = 1 shl tableBits
            tableShift = DM_LENGTH_SHIFT - tableBits
            decoderTable = IntArray(tableSize + 2)
        } else {
            decoderTable = null
            tableSize = 0
            tableShift = 0
        }
        distribution = IntArray(symbols)
        symbolCount = IntArray(symbols)
        totalCount = 0
        updateCycle = symbols
        for (k in 0 until symbols) symbolCount[k] = table?.get(k) ?: 1
        update()
        updateCycle = (symbols + 6) shr 1
        symbolsUntilUpdate = updateCycle
    }

    fun update() {
        totalCount += updateCycle
        if (totalCount > DM_MAX_COUNT) {
            totalCount = 0
            for (n in 0 until symbols) {
                symbolCount[n] = (symbolCount[n] + 1) shr 1
                totalCount += symbolCount[n]
            }
        }
        var sum = 0
        var s = 0
        val scale = 0x80000000u / totalCount.toUInt()
        val table = decoderTable
        if (table == null) {
            for (k in 0 until symbols) {
                distribution[k] = ((scale * sum.toUInt()) shr (31 - DM_LENGTH_SHIFT)).toInt()
                sum += symbolCount[k]
            }
        } else {
            for (k in 0 until symbols) {
                distribution[k] = ((scale * sum.toUInt()) shr (31 - DM_LENGTH_SHIFT)).toInt()
                sum += symbolCount[k]
                val w = distribution[k] shr tableShift
                while (s < w) { s++; table[s] = k - 1 }
            }
            table[0] = 0
            while (s <= tableSize) { s++; table[s] = symbols - 1 }
        }
        updateCycle = (5 * updateCycle) shr 2
        val maxCycle = (symbols + 6) shl 3
        if (updateCycle > maxCycle) updateCycle = maxCycle
        symbolsUntilUpdate = updateCycle
    }
}

/** The arithmetic decoder. Construct over a [ByteStreamIn] positioned at the chunk's coded data. */
internal class ArithmeticDecoder(val instream: ByteStreamIn) {
    private var value = 0u
    private var length = AC_MAX_LENGTH

    /** (Re)initialise the coder, loading the first 4 bytes into [value]. */
    fun init() {
        length = AC_MAX_LENGTH
        value = (instream.getByte() shl 24) or (instream.getByte() shl 16) or
            (instream.getByte() shl 8) or instream.getByte()
    }

    fun decodeBit(m: ArithmeticBitModel): Int {
        val x = m.bit0Prob * (length shr BM_LENGTH_SHIFT)
        val sym = if (value >= x) 1 else 0
        if (sym == 0) { length = x; m.bit0Count++ }
        else { value -= x; length -= x }
        if (length < AC_MIN_LENGTH) renorm()
        if (--m.bitsUntilUpdate == 0) m.update()
        return sym
    }

    fun decodeSymbol(m: ArithmeticModel): Int {
        var n: UInt
        var sym: UInt
        var x: UInt
        var y = length
        val table = m.decoderTable
        if (table != null) {
            length = length shr DM_LENGTH_SHIFT
            val dv = value / length
            val t = (dv shr m.tableShift).toInt()
            sym = table[t].toUInt()
            n = table[t + 1].toUInt() + 1u
            while (n > sym + 1u) {
                val k = (sym + n) shr 1
                if (m.distribution[k.toInt()].toUInt() > dv) n = k else sym = k
            }
            x = m.distribution[sym.toInt()].toUInt() * length
            if (sym.toInt() != m.lastSymbol) y = m.distribution[sym.toInt() + 1].toUInt() * length
        } else {
            x = 0u
            sym = 0u
            length = length shr DM_LENGTH_SHIFT
            n = m.symbols.toUInt()
            var k = n shr 1
            do {
                val z = length * m.distribution[k.toInt()].toUInt()
                if (z > value) { n = k; y = z } else { sym = k; x = z }
                k = (sym + n) shr 1
            } while (k != sym)
        }
        value -= x
        length = y - x
        if (length < AC_MIN_LENGTH) renorm()
        m.symbolCount[sym.toInt()]++
        if (--m.symbolsUntilUpdate == 0) m.update()
        return sym.toInt()
    }

    fun readBit(): Int {
        length = length shr 1
        val sym = value / length
        value -= length * sym
        if (length < AC_MIN_LENGTH) renorm()
        return sym.toInt()
    }

    fun readBits(bits: Int): UInt {
        if (bits > 19) {
            val lower = readBits(16)
            val upper = readBits(bits - 16)
            return (upper shl 16) or lower
        }
        length = length shr bits
        val sym = value / length
        value -= length * sym
        if (length < AC_MIN_LENGTH) renorm()
        return sym
    }

    fun readInt(): Int {
        val lower = readBits(16)
        val upper = readBits(16)
        return ((upper shl 16) or lower).toInt()
    }

    private fun renorm() {
        do {
            value = (value shl 8) or instream.getByte()
            length = length shl 8
        } while (length < AC_MIN_LENGTH)
    }
}
