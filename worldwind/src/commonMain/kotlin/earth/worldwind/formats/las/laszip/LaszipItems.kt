package earth.worldwind.formats.las.laszip

/**
 * Version-2 LASzip item decompressors ported from `lasreaditemcompressed_v2.cpp` and
 * `laszip_common_v2.hpp` (Apache-2.0, rapidlasso GmbH). Each decompressor owns the running
 * "last item" state for one item type and writes decoded bytes straight into the output
 * point record at a given offset. A fresh instance per chunk provides the per-chunk reset.
 */

internal interface ItemDecompressor {
    val size: Int
    /** Seed state from the chunk's raw first point (already present at [record]+[offset]). */
    fun initFromRaw(record: ByteArray, offset: Int)
    /** Decode the next point's bytes into [record] at [offset]. */
    fun read(record: ByteArray, offset: Int)
}

// --- byte helpers (all LAS fields are little-endian) ---

internal fun u8fold(n: Int) = if (n < 0) n + 256 else if (n > 255) n - 256 else n
internal fun u8clamp(n: Int) = if (n <= 0) 0 else if (n >= 255) 255 else n

internal fun getI32LE(b: ByteArray, o: Int) =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

internal fun setI32LE(b: ByteArray, o: Int, v: Int) {
    b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
}

internal fun getU16LE(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

internal fun setU16LE(b: ByteArray, o: Int, v: Int) {
    b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
}

/** LASzip "streaming median of 5" predictor. */
internal class StreamingMedian5 {
    private val values = IntArray(5)
    private var high = true

    fun reset() { values.fill(0); high = true }

    fun add(v: Int) {
        if (high) {
            if (v < values[2]) {
                values[4] = values[3]; values[3] = values[2]
                when {
                    v < values[0] -> { values[2] = values[1]; values[1] = values[0]; values[0] = v }
                    v < values[1] -> { values[2] = values[1]; values[1] = v }
                    else -> values[2] = v
                }
            } else {
                if (v < values[3]) { values[4] = values[3]; values[3] = v } else values[4] = v
                high = false
            }
        } else {
            if (values[2] < v) {
                values[0] = values[1]; values[1] = values[2]
                when {
                    values[4] < v -> { values[2] = values[3]; values[3] = values[4]; values[4] = v }
                    values[3] < v -> { values[2] = values[3]; values[3] = v }
                    else -> values[2] = v
                }
            } else {
                if (values[1] < v) { values[0] = values[1]; values[1] = v } else values[0] = v
                high = true
            }
        }
    }

    fun get() = values[2]
}

// Context maps for the return-number / number-of-returns combination (laszip_common_v2.hpp).
internal val NUMBER_RETURN_MAP = arrayOf(
    intArrayOf(15, 14, 13, 12, 11, 10, 9, 8),
    intArrayOf(14, 0, 1, 3, 6, 10, 10, 9),
    intArrayOf(13, 1, 2, 4, 7, 11, 11, 10),
    intArrayOf(12, 3, 4, 5, 8, 12, 12, 11),
    intArrayOf(11, 6, 7, 8, 9, 13, 13, 12),
    intArrayOf(10, 10, 11, 12, 13, 14, 14, 13),
    intArrayOf(9, 10, 11, 12, 13, 14, 15, 14),
    intArrayOf(8, 9, 10, 11, 12, 13, 14, 15),
)
internal val NUMBER_RETURN_LEVEL = arrayOf(
    intArrayOf(0, 1, 2, 3, 4, 5, 6, 7),
    intArrayOf(1, 0, 1, 2, 3, 4, 5, 6),
    intArrayOf(2, 1, 0, 1, 2, 3, 4, 5),
    intArrayOf(3, 2, 1, 0, 1, 2, 3, 4),
    intArrayOf(4, 3, 2, 1, 0, 1, 2, 3),
    intArrayOf(5, 4, 3, 2, 1, 0, 1, 2),
    intArrayOf(6, 5, 4, 3, 2, 1, 0, 1),
    intArrayOf(7, 6, 5, 4, 3, 2, 1, 0),
)

/** POINT10 (core 20-byte record for point formats 0–5). */
internal class Point10Decompressor(private val dec: ArithmeticDecoder) : ItemDecompressor {
    override val size = 20
    private val lastItem = ByteArray(20)
    private val lastIntensity = IntArray(16)
    private val lastXMedian = Array(16) { StreamingMedian5() }
    private val lastYMedian = Array(16) { StreamingMedian5() }
    private val lastHeight = IntArray(8)

    private val mChangedValues = ArithmeticModel(64).also { it.init() }
    private val icIntensity = IntegerCompressor(dec, 16, 4)
    private val mScanAngleRank = Array(2) { ArithmeticModel(256).also { it.init() } }
    private val icPointSourceId = IntegerCompressor(dec, 16)
    private val mBitByte = arrayOfNulls<ArithmeticModel>(256)
    private val mClassification = arrayOfNulls<ArithmeticModel>(256)
    private val mUserData = arrayOfNulls<ArithmeticModel>(256)
    private val icDx = IntegerCompressor(dec, 32, 2)
    private val icDy = IntegerCompressor(dec, 32, 22)
    private val icZ = IntegerCompressor(dec, 32, 20)

    override fun initFromRaw(record: ByteArray, offset: Int) {
        record.copyInto(lastItem, 0, offset, offset + 20)
        // prediction state starts from zero intensity (spec)
        lastItem[12] = 0; lastItem[13] = 0
    }

    override fun read(record: ByteArray, offset: Int) {
        val changed = dec.decodeSymbol(mChangedValues)
        val r: Int; val n: Int
        if (changed != 0) {
            if (changed and 32 != 0) {
                val idx = lastItem[14].toInt() and 0xFF
                val m = mBitByte[idx] ?: ArithmeticModel(256).also { it.init(); mBitByte[idx] = it }
                lastItem[14] = dec.decodeSymbol(m).toByte()
            }
            r = lastItem[14].toInt() and 0x07
            n = (lastItem[14].toInt() shr 3) and 0x07
            val m = NUMBER_RETURN_MAP[n][r]
            if (changed and 16 != 0) {
                val newI = icIntensity.decompress(lastIntensity[m], if (m < 3) m else 3)
                setU16LE(lastItem, 12, newI and 0xFFFF)
                lastIntensity[m] = newI and 0xFFFF
            } else {
                setU16LE(lastItem, 12, lastIntensity[m])
            }
            if (changed and 8 != 0) {
                val idx = lastItem[15].toInt() and 0xFF
                val mc = mClassification[idx] ?: ArithmeticModel(256).also { it.init(); mClassification[idx] = it }
                lastItem[15] = dec.decodeSymbol(mc).toByte()
            }
            if (changed and 4 != 0) {
                val sdf = (lastItem[14].toInt() shr 6) and 0x01
                val v = dec.decodeSymbol(mScanAngleRank[sdf])
                lastItem[16] = u8fold(v + (lastItem[16].toInt() and 0xFF)).toByte()
            }
            if (changed and 2 != 0) {
                val idx = lastItem[17].toInt() and 0xFF
                val mu = mUserData[idx] ?: ArithmeticModel(256).also { it.init(); mUserData[idx] = it }
                lastItem[17] = dec.decodeSymbol(mu).toByte()
            }
            if (changed and 1 != 0) {
                setU16LE(lastItem, 18, icPointSourceId.decompress(getU16LE(lastItem, 18)) and 0xFFFF)
            }
        } else {
            r = lastItem[14].toInt() and 0x07
            n = (lastItem[14].toInt() shr 3) and 0x07
        }
        val m = NUMBER_RETURN_MAP[n][r]
        val l = NUMBER_RETURN_LEVEL[n][r]
        val nIsOne = if (n == 1) 1 else 0

        // x
        var median = lastXMedian[m].get()
        var diff = icDx.decompress(median, nIsOne)
        setI32LE(lastItem, 0, getI32LE(lastItem, 0) + diff)
        lastXMedian[m].add(diff)
        // y
        median = lastYMedian[m].get()
        var kBits = icDx.k
        diff = icDy.decompress(median, nIsOne + (if (kBits < 20) kBits and 0x7FFFFFFE else 20))
        setI32LE(lastItem, 4, getI32LE(lastItem, 4) + diff)
        lastYMedian[m].add(diff)
        // z
        kBits = (icDx.k + icDy.k) / 2
        val z = icZ.decompress(lastHeight[l], nIsOne + (if (kBits < 18) kBits and 0x7FFFFFFE else 18))
        setI32LE(lastItem, 8, z)
        lastHeight[l] = z

        lastItem.copyInto(record, offset, 0, 20)
    }
}

/** GPSTIME11 (8-byte GPS time double, stored as int64 deltas). */
internal class GpsTime11Decompressor(private val dec: ArithmeticDecoder) : ItemDecompressor {
    override val size = 8
    private var last = 0
    private var next = 0
    private val lastGpstime = LongArray(4)
    private val lastGpstimeDiff = IntArray(4)
    private val multiExtremeCounter = IntArray(4)

    private val mGpstimeMulti = ArithmeticModel(GPSTIME_MULTI_TOTAL).also { it.init() }
    private val mGpstime0diff = ArithmeticModel(6).also { it.init() }
    private val icGpstime = IntegerCompressor(dec, 32, 9)

    override fun initFromRaw(record: ByteArray, offset: Int) {
        last = 0; next = 0
        lastGpstimeDiff.fill(0); multiExtremeCounter.fill(0)
        lastGpstime[0] = readI64LE(record, offset)
        lastGpstime[1] = 0; lastGpstime[2] = 0; lastGpstime[3] = 0
    }

    override fun read(record: ByteArray, offset: Int) {
        decodeStep()
        writeI64LE(record, offset, lastGpstime[last])
    }

    private fun decodeStep() {
        if (lastGpstimeDiff[last] == 0) {
            val multi = dec.decodeSymbol(mGpstime0diff)
            when {
                multi == 1 -> {
                    lastGpstimeDiff[last] = icGpstime.decompress(0, 0)
                    lastGpstime[last] += lastGpstimeDiff[last].toLong()
                    multiExtremeCounter[last] = 0
                }
                multi == 2 -> {
                    next = (next + 1) and 3
                    var t = icGpstime.decompress((lastGpstime[last] ushr 32).toInt(), 8).toLong() and 0xFFFFFFFFL
                    t = t shl 32
                    t = t or (dec.readInt().toLong() and 0xFFFFFFFFL)
                    lastGpstime[next] = t
                    last = next
                    lastGpstimeDiff[last] = 0
                    multiExtremeCounter[last] = 0
                }
                multi > 2 -> {
                    last = (last + multi - 2) and 3
                    decodeStep()
                }
            }
        } else {
            val multi = dec.decodeSymbol(mGpstimeMulti)
            if (multi == 1) {
                lastGpstime[last] += icGpstime.decompress(lastGpstimeDiff[last], 1).toLong()
                multiExtremeCounter[last] = 0
            } else if (multi < GPSTIME_MULTI_UNCHANGED) {
                val gpstimeDiff: Int
                if (multi == 0) {
                    gpstimeDiff = icGpstime.decompress(0, 7)
                    multiExtremeCounter[last]++
                    if (multiExtremeCounter[last] > 3) { lastGpstimeDiff[last] = gpstimeDiff; multiExtremeCounter[last] = 0 }
                } else if (multi < GPSTIME_MULTI) {
                    gpstimeDiff = if (multi < 10) icGpstime.decompress(multi * lastGpstimeDiff[last], 2)
                    else icGpstime.decompress(multi * lastGpstimeDiff[last], 3)
                } else if (multi == GPSTIME_MULTI) {
                    gpstimeDiff = icGpstime.decompress(GPSTIME_MULTI * lastGpstimeDiff[last], 4)
                    multiExtremeCounter[last]++
                    if (multiExtremeCounter[last] > 3) { lastGpstimeDiff[last] = gpstimeDiff; multiExtremeCounter[last] = 0 }
                } else {
                    val m2 = GPSTIME_MULTI - multi
                    if (m2 > GPSTIME_MULTI_MINUS) {
                        gpstimeDiff = icGpstime.decompress(m2 * lastGpstimeDiff[last], 5)
                    } else {
                        gpstimeDiff = icGpstime.decompress(GPSTIME_MULTI_MINUS * lastGpstimeDiff[last], 6)
                        multiExtremeCounter[last]++
                        if (multiExtremeCounter[last] > 3) { lastGpstimeDiff[last] = gpstimeDiff; multiExtremeCounter[last] = 0 }
                    }
                }
                lastGpstime[last] += gpstimeDiff.toLong()
            } else if (multi == GPSTIME_MULTI_CODE_FULL) {
                next = (next + 1) and 3
                var t = icGpstime.decompress((lastGpstime[last] ushr 32).toInt(), 8).toLong() and 0xFFFFFFFFL
                t = t shl 32
                t = t or (dec.readInt().toLong() and 0xFFFFFFFFL)
                lastGpstime[next] = t
                last = next
                lastGpstimeDiff[last] = 0
                multiExtremeCounter[last] = 0
            } else if (multi >= GPSTIME_MULTI_CODE_FULL) {
                last = (last + multi - GPSTIME_MULTI_CODE_FULL) and 3
                decodeStep()
            }
        }
    }

    private companion object {
        const val GPSTIME_MULTI = 500
        const val GPSTIME_MULTI_MINUS = -10
        const val GPSTIME_MULTI_UNCHANGED = GPSTIME_MULTI - GPSTIME_MULTI_MINUS + 1
        const val GPSTIME_MULTI_CODE_FULL = GPSTIME_MULTI - GPSTIME_MULTI_MINUS + 2
        const val GPSTIME_MULTI_TOTAL = GPSTIME_MULTI - GPSTIME_MULTI_MINUS + 6
    }
}

private fun readI64LE(b: ByteArray, o: Int): Long {
    val lo = getI32LE(b, o).toLong() and 0xFFFFFFFFL
    val hi = getI32LE(b, o + 4).toLong() and 0xFFFFFFFFL
    return (hi shl 32) or lo
}

private fun writeI64LE(b: ByteArray, o: Int, v: Long) {
    setI32LE(b, o, v.toInt())
    setI32LE(b, o + 4, (v ushr 32).toInt())
}

/** RGB12 (6-byte red/green/blue triple, 16-bit channels). */
internal class Rgb12Decompressor(private val dec: ArithmeticDecoder) : ItemDecompressor {
    override val size = 6
    private val last = IntArray(3)

    private val mByteUsed = ArithmeticModel(128).also { it.init() }
    private val mDiff0 = ArithmeticModel(256).also { it.init() }
    private val mDiff1 = ArithmeticModel(256).also { it.init() }
    private val mDiff2 = ArithmeticModel(256).also { it.init() }
    private val mDiff3 = ArithmeticModel(256).also { it.init() }
    private val mDiff4 = ArithmeticModel(256).also { it.init() }
    private val mDiff5 = ArithmeticModel(256).also { it.init() }

    override fun initFromRaw(record: ByteArray, offset: Int) {
        last[0] = getU16LE(record, offset)
        last[1] = getU16LE(record, offset + 2)
        last[2] = getU16LE(record, offset + 4)
    }

    override fun read(record: ByteArray, offset: Int) {
        val sym = dec.decodeSymbol(mByteUsed)
        var item0: Int
        item0 = if (sym and 1 != 0) u8fold(dec.decodeSymbol(mDiff0) + (last[0] and 255)) else last[0] and 0xFF
        item0 = if (sym and 2 != 0) item0 or (u8fold(dec.decodeSymbol(mDiff1) + (last[0] shr 8)) shl 8)
        else item0 or (last[0] and 0xFF00)

        var item1: Int
        var item2: Int
        if (sym and 64 != 0) {
            var diff = (item0 and 0xFF) - (last[0] and 0xFF)
            item1 = if (sym and 4 != 0) u8fold(dec.decodeSymbol(mDiff2) + u8clamp(diff + (last[1] and 255)))
            else last[1] and 0xFF
            item2 = if (sym and 16 != 0) {
                diff = (diff + ((item1 and 0xFF) - (last[1] and 0xFF))) / 2
                u8fold(dec.decodeSymbol(mDiff4) + u8clamp(diff + (last[2] and 255)))
            } else last[2] and 0xFF
            diff = (item0 shr 8) - (last[0] shr 8)
            item1 = if (sym and 8 != 0) item1 or (u8fold(dec.decodeSymbol(mDiff3) + u8clamp(diff + (last[1] shr 8))) shl 8)
            else item1 or (last[1] and 0xFF00)
            item2 = if (sym and 32 != 0) {
                diff = (diff + ((item1 shr 8) - (last[1] shr 8))) / 2
                item2 or (u8fold(dec.decodeSymbol(mDiff5) + u8clamp(diff + (last[2] shr 8))) shl 8)
            } else item2 or (last[2] and 0xFF00)
        } else {
            item1 = item0
            item2 = item0
        }
        last[0] = item0; last[1] = item1; last[2] = item2
        setU16LE(record, offset, item0)
        setU16LE(record, offset + 2, item1)
        setU16LE(record, offset + 4, item2)
    }
}

/** BYTE (extra-bytes block of [number] independent bytes). */
internal class ByteDecompressor(private val dec: ArithmeticDecoder, private val number: Int) : ItemDecompressor {
    override val size = number
    private val last = ByteArray(number)
    private val mByte = Array(number) { ArithmeticModel(256).also { it.init() } }

    override fun initFromRaw(record: ByteArray, offset: Int) {
        record.copyInto(last, 0, offset, offset + number)
    }

    override fun read(record: ByteArray, offset: Int) {
        for (i in 0 until number) {
            val value = (last[i].toInt() and 0xFF) + dec.decodeSymbol(mByte[i])
            val folded = u8fold(value).toByte()
            record[offset + i] = folded
            last[i] = folded
        }
    }
}
