package earth.worldwind.formats.las.laszip

import kotlin.math.min

/**
 * Decoder-side port of LASzip's `IntegerCompressor` (`integercompressor.cpp`). Decodes a
 * predicted integer by range-decoding the corrector's bit-length `k`, then the corrector's
 * position within that interval. Models are created and initialised on construction, matching
 * `initDecompressor()` — a fresh instance per chunk gives the required per-chunk reset.
 */
internal class IntegerCompressor(
    private val dec: ArithmeticDecoder,
    bits: Int = 16,
    contexts: Int = 1,
    private val bitsHigh: Int = 8,
    range: Int = 0,
) {
    private val corrBits: Int
    private val corrRange: Int
    private val corrMin: Int

    /** Bit-length of the last decoded corrector; POINT10 feeds it back as context. */
    var k = 0
        private set

    private val mBits: Array<ArithmeticModel>
    private val mCorrector0: ArithmeticBitModel
    private val mCorrector: Array<ArithmeticModel?>

    init {
        if (range != 0) {
            var r = range
            var cb = 0
            while (r != 0) { r = r shr 1; cb++ }
            if (range == (1 shl (cb - 1))) cb--
            corrBits = cb
            corrRange = range
            corrMin = -(range / 2)
        } else if (bits in 1..31) {
            corrBits = bits
            corrRange = 1 shl bits
            corrMin = -(corrRange / 2)
        } else {
            corrBits = 32
            corrRange = 0
            corrMin = Int.MIN_VALUE
        }

        mBits = Array(contexts) { ArithmeticModel(corrBits + 1).also { it.init() } }
        mCorrector0 = ArithmeticBitModel()
        mCorrector = Array(corrBits + 1) { i ->
            if (i == 0) null else ArithmeticModel(1 shl min(i, bitsHigh)).also { it.init() }
        }
    }

    fun decompress(pred: Int, context: Int = 0): Int {
        var real = pred + readCorrector(mBits[context])
        if (real < 0) real += corrRange
        else if (real.toUInt() >= corrRange.toUInt()) real -= corrRange
        return real
    }

    private fun readCorrector(model: ArithmeticModel): Int {
        k = dec.decodeSymbol(model)
        if (k != 0) {
            if (k < 32) {
                var c: Int
                if (k <= bitsHigh) {
                    c = dec.decodeSymbol(mCorrector[k]!!)
                } else {
                    val k1 = k - bitsHigh
                    c = dec.decodeSymbol(mCorrector[k]!!)
                    val c1 = dec.readBits(k1).toInt()
                    c = (c shl k1) or c1
                }
                // translate c back into its correct signed interval
                return if (c >= (1 shl (k - 1))) c + 1 else c - ((1 shl k) - 1)
            }
            return corrMin
        }
        return dec.decodeBit(mCorrector0)
    }
}
