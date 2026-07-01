package earth.worldwind.formats.las.laszip

import earth.worldwind.formats.las.LasHeader
import earth.worldwind.formats.las.LaszipItem
import earth.worldwind.formats.las.LaszipVlr
import earth.worldwind.formats.las.LazChunkDecoder
import earth.worldwind.formats.las.RenderYield

/**
 * Pure-Kotlin LASzip decompressor for the pointwise-chunked compressor (the dominant real-world
 * `.laz` layout, point formats 0–3). Reconstructs standard ASPRS point records so
 * [earth.worldwind.formats.las.LasReader] reads them with its uncompressed field path.
 *
 * Each chunk stores its first point raw, then range-codes the remaining points; the chunk table
 * (delta-coded byte offsets) lets us decode each chunk from an exact stream position. LAS 1.4
 * layered compression (formats 6–10) and the legacy pointwise (v1) compressor are out of scope —
 * they surface as a clear error; the uncompressed forms of those formats still load.
 *
 * Install once at app startup: `LasDecoderRegistry.lazDecoder = LaszipDecoder()`.
 */
class LaszipDecoder : LazChunkDecoder {
    override suspend fun decode(fileBytes: ByteArray, header: LasHeader, vlr: LaszipVlr): ByteArray {
        require(vlr.compressor == LaszipVlr.COMPRESSOR_POINTWISE_CHUNKED) {
            "unsupported LASzip compressor ${vlr.compressor} (only pointwise-chunked point formats 0–3 are supported)"
        }
        require(vlr.chunkSize > 0) { "adaptive-size LASzip chunks are not supported" }
        vlr.items.forEach { requireSupported(it) }

        val pointSize = vlr.items.sumOf { it.size }
        val n = header.pointCount.toInt()
        val out = ByteArray(n * pointSize)

        val offsets = IntArray(vlr.items.size)
        var acc = 0
        for (i in vlr.items.indices) { offsets[i] = acc; acc += vlr.items[i].size }

        val head = ByteStreamIn(fileBytes)
        head.seek(header.offsetToPointData)
        val chunkTableStart = head.getInt64LE()
        val chunksStart = header.offsetToPointData + 8
        val chunkSize = vlr.chunkSize
        val numChunks = (n + chunkSize - 1) / chunkSize
        val chunkStarts = readChunkTable(fileBytes, chunkTableStart, chunksStart, numChunks)

        // Keeps JS/wasm responsive during decode.
        val yielder = RenderYield()
        var pointIndex = 0
        var cursor = chunksStart
        for (c in 0 until numChunks) {
            val start = chunkStarts?.getOrNull(c)?.toInt() ?: cursor
            val count = minOf(chunkSize, n - c * chunkSize)
            cursor = decodeChunk(fileBytes, start, count, vlr.items, offsets, pointSize, out, pointIndex, yielder)
            pointIndex += count
        }
        return out
    }

    private suspend fun decodeChunk(
        fileBytes: ByteArray, start: Int, count: Int, items: List<LaszipItem>, offsets: IntArray,
        pointSize: Int, out: ByteArray, pointIndex: Int, yielder: RenderYield,
    ): Int {
        val base = pointIndex * pointSize
        // The chunk's first point is stored raw; it also seeds every item's prediction state.
        fileBytes.copyInto(out, base, start, start + pointSize)
        val stream = ByteStreamIn(fileBytes)
        stream.seek(start + pointSize)
        val dec = ArithmeticDecoder(stream)
        val decompressors = items.map { createDecompressor(dec, it) }
        for (i in items.indices) decompressors[i].initFromRaw(out, base + offsets[i])
        dec.init()
        for (j in 1 until count) {
            if (j and 0xFF == 0) yielder.tick() // yield when the frame budget is spent
            val pbase = base + j * pointSize
            for (i in items.indices) decompressors[i].read(out, pbase + offsets[i])
        }
        return stream.pos
    }

    /** Delta-coded chunk-start byte offsets, or null when no usable table is present (a
     *  truncated/interrupted compressor) — the caller then decodes chunks back-to-back. */
    private fun readChunkTable(fileBytes: ByteArray, chunkTableStartIn: Long, chunksStart: Int, numChunks: Int): LongArray? {
        var chunkTableStart = chunkTableStartIn
        if (chunkTableStart == -1L) {
            if (fileBytes.size < 8) return null
            val end = ByteStreamIn(fileBytes)
            end.seek(fileBytes.size - 8)
            chunkTableStart = end.getInt64LE()
        }
        if (chunkTableStart <= 0 || chunkTableStart > fileBytes.size - 8L) return null
        if (chunkTableStart + 8 == chunksStart.toLong()) return null // interrupted before writing the table

        val s = ByteStreamIn(fileBytes)
        s.seek(chunkTableStart.toInt())
        val version = s.getInt32LE()
        if (version != 0) return null
        val tableChunks = s.getInt32LE()
        if (tableChunks <= 0 || tableChunks < numChunks) return null

        val starts = LongArray(tableChunks + 1)
        starts[0] = chunksStart.toLong()
        val dec = ArithmeticDecoder(s)
        dec.init()
        val ic = IntegerCompressor(dec, 32, 2)
        for (i in 1..tableChunks) {
            val pred = if (i > 1) starts[i - 1].toInt() else 0
            starts[i] = ic.decompress(pred, 1).toLong() and 0xFFFFFFFFL
        }
        for (i in 1..tableChunks) starts[i] += starts[i - 1]
        return starts
    }

    private fun createDecompressor(dec: ArithmeticDecoder, item: LaszipItem): ItemDecompressor = when (item.type) {
        LaszipVlr.ITEM_POINT10 -> Point10Decompressor(dec)
        LaszipVlr.ITEM_GPSTIME11 -> GpsTime11Decompressor(dec)
        LaszipVlr.ITEM_RGB12 -> Rgb12Decompressor(dec)
        LaszipVlr.ITEM_BYTE -> ByteDecompressor(dec, item.size)
        else -> error("unsupported LASzip item type ${item.type}")
    }

    private fun requireSupported(item: LaszipItem) {
        val supported = item.type == LaszipVlr.ITEM_POINT10 || item.type == LaszipVlr.ITEM_GPSTIME11 ||
            item.type == LaszipVlr.ITEM_RGB12 || item.type == LaszipVlr.ITEM_BYTE
        require(supported) {
            "unsupported LASzip item type ${item.type} (LAS 1.4 layered point formats 6–10 are not supported yet; " +
                "use the uncompressed .las form)"
        }
    }
}
