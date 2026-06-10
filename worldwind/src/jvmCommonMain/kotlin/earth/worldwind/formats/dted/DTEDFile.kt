package earth.worldwind.formats.dted

import earth.worldwind.formats.BinaryDataView
import earth.worldwind.formats.dted.DTED.Companion.DATA_OFFSET
import earth.worldwind.formats.dted.DTED.Companion.INVALID_POST
import earth.worldwind.formats.dted.DTED.Companion.REC_HEADER_SIZE
import earth.worldwind.formats.dted.DTED.Companion.UHL_SIZE
import earth.worldwind.formats.dted.DTED.Companion.computeRange
import earth.worldwind.formats.dted.DTED.Companion.decodeColumn
import earth.worldwind.formats.dted.DTED.Companion.decodePost
import earth.worldwind.formats.dted.DTED.Companion.parseHeader
import earth.worldwind.formats.dted.DTED.Companion.parseUhl
import earth.worldwind.formats.dted.DTED.Companion.recordSize
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import kotlin.math.floor
import kotlin.math.roundToInt

private const val UHL_CACHE_MAX = 4096

/** Bounded LRU of parsed UHLs keyed by (path, mtime, size): skips re-parsing a tile's header, can't grow
 *  unbounded, and re-parses a file replaced in place (its mtime/size change the key). */
private val uhlCache: MutableMap<String, DTED.Metadata> = Collections.synchronizedMap(
    object : LinkedHashMap<String, DTED.Metadata>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DTED.Metadata>?) = size > UHL_CACHE_MAX
    }
)

/** Cached UHL metadata for [file]; reads + parses the 80-byte UHL on a miss. Callers always seek before
 *  reading columns, so [raf]'s cursor is left wherever this lands. */
private fun cachedUhl(raf: RandomAccessFile, file: File): DTED.Metadata =
    uhlCache.getOrPut("${file.path}|${file.lastModified()}|${file.length()}") {
        val headerBytes = ByteArray(UHL_SIZE)
        raf.readFully(headerBytes)
        parseUhl(BinaryDataView(headerBytes))
    }

/** Read the full UHL/DSI/ACC headers (first 3428 bytes) — when the DTED level/classification are needed. */
fun DTED.Companion.readMetadata(file: File): DTED.Metadata =
    RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(DATA_OFFSET)
        raf.readFully(header)
        parseHeader(BinaryDataView(header))
    }

/** Streaming reader: walks column records one at a time, so peak memory is ~7 KB (one record) not ~26 MB. */
fun DTED.Companion.read(file: File): DTED = RandomAccessFile(file, "r").use { raf ->
    val headerBytes = ByteArray(DATA_OFFSET)
    raf.readFully(headerBytes)
    val meta = parseHeader(BinaryDataView(headerBytes))
    val rec = recordSize(meta.height)
    val elevations = ShortArray(meta.width * meta.height)
    val recordBytes = ByteArray(rec)
    val recordView = BinaryDataView(recordBytes)
    for (x in 0 until meta.width) {
        raf.readFully(recordBytes)
        decodeColumn(recordView, 0, x, meta.width, meta.height, elevations) // record is self-contained → offset 0

    }
    val (minE, maxE) = computeRange(elevations)
    DTED(
        meta.sector, meta.origin, meta.width, meta.height, meta.level, meta.classLevel,
        elevations, minE, maxE
    )
}

/**
 * Windowed streaming reader: **bilinear**-resamples a cell-relative sub-rectangle (`colFrac`/`rowFrac` in
 * `[0,1]`) to [targetWidth] × [targetHeight], reading only the two columns and post span the window touches.
 * Bilinear so coarse levels upsample smoothly (dt2 at 1:1 is exact); a sample with any invalid corner →
 * [Short.MIN_VALUE]. For render-path speed it skips the per-record checksum ([DTED.parse] validates).
 */
fun DTED.Companion.read(
    file: File,
    colFrac0: Double, colFrac1: Double, rowFrac0: Double, rowFrac1: Double,
    targetWidth: Int, targetHeight: Int,
): ShortArray = RandomAccessFile(file, "r").use { raf ->
    val meta = cachedUhl(raf, file)
    val width = meta.width
    val height = meta.height
    val rec = recordSize(height)
    val out = ShortArray(targetWidth * targetHeight)
    val lastTx = (targetWidth - 1).coerceAtLeast(1)
    val lastTy = (targetHeight - 1).coerceAtLeast(1)
    val maxRow = (height - 1).toDouble()
    val maxCol = (width - 1).toDouble()
    // Per output row: the two bracketing source posts (south=0 .. north=height-1) + bilinear weight.
    val rowP0 = IntArray(targetHeight)
    val rowP1 = IntArray(targetHeight)
    val rowW = FloatArray(targetHeight)
    var minP = Int.MAX_VALUE
    var maxP = Int.MIN_VALUE
    for (ty in 0 until targetHeight) {
        val northFrac = rowFrac0 + (rowFrac1 - rowFrac0) * ty / lastTy // 0 = north, 1 = south
        val srcPostF = (maxRow * (1.0 - northFrac)).coerceIn(0.0, maxRow) // post index, south = 0
        val p0 = floor(srcPostF).toInt()
        val p1 = (p0 + 1).coerceAtMost(height - 1)
        rowP0[ty] = p0; rowP1[ty] = p1; rowW[ty] = (srcPostF - p0).toFloat()
        if (p0 < minP) minP = p0
        if (p1 > maxP) maxP = p1
    }
    val baseByte = REC_HEADER_SIZE + minP * 2
    val span = (maxP - minP) * 2 + 2 // +2 to include the 2-byte post at maxP
    val colBytes0 = ByteArray(span)
    val colBytes1 = ByteArray(span)
    var loaded0 = -1
    var loaded1 = -1

    fun readCol(srcCol: Int, buf: ByteArray) {
        raf.seek(DATA_OFFSET + srcCol.toLong() * rec + baseByte)
        raf.readFully(buf)
    }
    // Decoded post value from a column buffer, or [INVALID_POST] for NODATA / out-of-range.
    fun postOf(buf: ByteArray, p: Int): Int {
        val o = (p - minP) * 2
        return decodePost(buf[o].toInt(), buf[o + 1].toInt())
    }

    for (tx in 0 until targetWidth) {
        val srcColF = ((colFrac0 + (colFrac1 - colFrac0) * tx / lastTx) * maxCol).coerceIn(0.0, maxCol)
        val c0 = floor(srcColF).toInt()
        val c1 = (c0 + 1).coerceAtMost(width - 1)
        val wCol = (srcColF - c0).toFloat()
        if (c0 != loaded0) {
            if (c0 == loaded1) { colBytes1.copyInto(colBytes0); loaded0 = c0 } else { readCol(c0, colBytes0); loaded0 = c0 }
        }
        if (c1 != loaded1) {
            if (c1 == loaded0) { colBytes0.copyInto(colBytes1); loaded1 = c1 } else { readCol(c1, colBytes1); loaded1 = c1 }
        }
        var outIdx = tx
        for (ty in 0 until targetHeight) {
            val p0 = rowP0[ty]; val p1 = rowP1[ty]; val wRow = rowW[ty]
            val a = postOf(colBytes0, p0); val b = postOf(colBytes1, p0)
            val c = postOf(colBytes0, p1); val d = postOf(colBytes1, p1)
            out[outIdx] = if (a == INVALID_POST || b == INVALID_POST || c == INVALID_POST || d == INVALID_POST) Short.MIN_VALUE
            else {
                val top = a * (1f - wCol) + b * wCol
                val bot = c * (1f - wCol) + d * wCol
                (top * (1f - wRow) + bot * wRow).roundToInt().toShort()
            }
            outIdx += targetWidth
        }
    }
    out
}

/** Full-cell convenience: resample the entire native grid to [targetWidth] × [targetHeight]. */
fun DTED.Companion.read(file: File, targetWidth: Int, targetHeight: Int): ShortArray =
    read(file, 0.0, 1.0, 0.0, 1.0, targetWidth, targetHeight)

/**
 * Coarse [n]×[n] overview (north-up, row-major): nearest-samples [n] evenly-spaced columns in one pass each.
 * Cheap path for zoomed-out grids — ~[n] column reads vs the window's ~2·[n], no rebuffering/interpolation.
 * Invalid posts → [Short.MIN_VALUE]; like the windowed read it skips per-record checksums.
 */
fun DTED.Companion.readOverview(file: File, n: Int): ShortArray = RandomAccessFile(file, "r").use { raf ->
    val meta = cachedUhl(raf, file)
    val width = meta.width
    val height = meta.height
    val rec = recordSize(height)
    val out = ShortArray(n * n)
    val colBytes = ByteArray(height * 2) // one column's posts (record minus its header + checksum)
    val lastN = (n - 1).coerceAtLeast(1)
    // Source post index (south = 0) for each output row, north-up: row 0 → northernmost post.
    val srcRow = IntArray(n) { ty -> ((height - 1).toLong() * (lastN - ty) / lastN).toInt() }
    for (tx in 0 until n) {
        val srcCol = ((width - 1).toLong() * tx / lastN).toInt()
        raf.seek(DATA_OFFSET + srcCol.toLong() * rec + REC_HEADER_SIZE)
        raf.readFully(colBytes)
        for (ty in 0 until n) {
            val o = srcRow[ty] * 2
            val v = decodePost(colBytes[o].toInt(), colBytes[o + 1].toInt())
            out[ty * n + tx] = if (v == INVALID_POST) Short.MIN_VALUE else v.toShort()
        }
    }
    out
}
