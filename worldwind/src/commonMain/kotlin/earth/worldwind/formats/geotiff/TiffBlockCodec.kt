package earth.worldwind.formats.geotiff

import earth.worldwind.formats.BinaryDataView
import earth.worldwind.formats.Inflate

/**
 * Decodes one strip / tile of a [TiffDirectory] into a flat chunky `FloatArray`
 * (`blockWidth * rowsInBlock * samplesPerPixel` samples, row-major, sample-interleaved).
 *
 * Float is the common currency because a block feeds two very different consumers — ARGB
 * conversion for imagery and metre elevations for terrain — and it holds every sample
 * layout GeoTIFF DEMs and orthophotos actually use (uint8/16, int16, float32) without
 * loss. Int32 / uint32 / float64 rasters are decoded too, with float's 24-bit mantissa the
 * limit on magnitude precision.
 *
 * Supported compressions: none, LZW, Deflate (both tag spellings), PackBits — plus
 * horizontal (2) and floating-point (3) predictors. JPEG-in-TIFF is not decoded here;
 * such blocks come back `null` and render as a hole rather than as noise.
 */
internal object TiffBlockCodec {
    private const val COMPRESSION_DEFLATE = 32946
    private const val COMPRESSION_ADOBE_DEFLATE = 8

    /**
     * Decode block [index]. Returns `null` for a sparse block (GDAL writes offset/count 0
     * for all-nodata blocks), an unsupported compression, or a truncated read — every one
     * of which the caller renders as missing data.
     */
    fun decodeBlock(
        source: TiffDataSource, dir: TiffDirectory, isLittleEndian: Boolean, index: Int
    ): FloatArray? {
        if (index < 0 || index >= dir.blockOffsets.size) return null
        val byteCount = dir.blockByteCounts.getOrElse(index) { 0L }
        val offset = dir.blockOffsets[index]
        if (byteCount <= 0L || offset <= 0L) return null // sparse block — no data stored
        if (byteCount > MAX_BLOCK_BYTES) {
            dir.warnOnce("GeoTIFF block $index is implausibly large ($byteCount bytes)")
            return null
        }
        if (dir.planarConfiguration == TiffConstants.PlanarConfiguration.PLANAR && dir.samplesPerPixel > 1) {
            dir.warnOnce("GeoTIFF planar (band-separated) layout is not supported")
            return null
        }
        val bits = dir.bitsPerFirstSample
        if (bits != 8 && bits != 16 && bits != 32 && bits != 64 || dir.bitsPerSample.any { it != bits }) {
            dir.warnOnce("GeoTIFF sample size ${dir.bitsPerSample.joinToString()} bits is not supported")
            return null
        }

        // Tiles are always stored full-size; the last strip row holds only the remaining rows.
        val blockY = index / dir.blocksAcross
        val rowsInBlock = if (dir.isTiled) dir.blockHeight
        else (dir.imageHeight - blockY * dir.blockHeight).coerceIn(1, dir.blockHeight)
        val bytesPerPixel = dir.bytesPerPixel
        val expectedBytes = dir.blockWidth * rowsInBlock * bytesPerPixel

        val raw = source.read(offset, byteCount.toInt())
        if (raw.isEmpty()) return null
        // A malformed bitstream is a damaged tile, not a broken layer: decompressors are
        // allowed to throw and the block becomes a hole like any other undecodable one.
        val bytes = try {
            when (dir.compression) {
                TiffConstants.Compression.UNCOMPRESSED ->
                    if (raw.size >= expectedBytes) raw else raw.copyOf(expectedBytes)
                TiffConstants.Compression.LZW ->
                    LzwDecoder.decode(BinaryDataView(raw), 0, raw.size, expectedBytes)
                COMPRESSION_DEFLATE, COMPRESSION_ADOBE_DEFLATE ->
                    Inflate.inflate(raw, 0, raw.size, expectedBytes)
                TiffConstants.Compression.PACK_BITS -> unpackBits(raw, expectedBytes)
                else -> {
                    dir.warnOnce("GeoTIFF compression ${dir.compression} is not supported")
                    return null
                }
            }
        } catch (ignored: Throwable) {
            dir.warnOnce("GeoTIFF block $index failed to decompress")
            return null
        }

        when (dir.predictor) {
            PREDICTOR_HORIZONTAL -> undoHorizontalPredictor(
                bytes, dir.blockWidth, rowsInBlock, dir.samplesPerPixel, bits / 8, isLittleEndian
            )
            PREDICTOR_FLOATING_POINT -> undoFloatingPointPredictor(
                bytes, dir.blockWidth, rowsInBlock, dir.samplesPerPixel, bits / 8
            )
        }

        return unpackSamples(bytes, dir, rowsInBlock, bits, isLittleEndian)
    }

    /** PackBits (TIFF 6 Appendix B): a run-length scheme of literal and repeated byte runs. */
    private fun unpackBits(src: ByteArray, expectedSize: Int): ByteArray {
        val out = ByteArray(expectedSize)
        var inPos = 0
        var outPos = 0
        while (inPos < src.size && outPos < expectedSize) {
            val n = src[inPos++].toInt()
            when {
                n >= 0 -> { // n + 1 literal bytes
                    val count = minOf(n + 1, src.size - inPos, expectedSize - outPos)
                    src.copyInto(out, outPos, inPos, inPos + count)
                    inPos += n + 1
                    outPos += count
                }
                n > -128 -> { // one byte repeated 1 - n times
                    if (inPos >= src.size) break
                    val value = src[inPos++]
                    var count = minOf(1 - n, expectedSize - outPos)
                    while (count-- > 0) out[outPos++] = value
                }
                // n == -128 is a no-op filler byte
            }
        }
        return out
    }

    /** Undo horizontal differencing (tag 317 = 2): each sample was stored as its delta from the
     *  previous sample of the same band in the same row. GDAL turns this on by default with LZW
     *  and Deflate because terrain and imagery are strongly correlated along a scanline. */
    private fun undoHorizontalPredictor(
        bytes: ByteArray, width: Int, rows: Int, samplesPerPixel: Int, bytesPerSample: Int, isLittleEndian: Boolean
    ) {
        val rowBytes = width * samplesPerPixel * bytesPerSample
        if (bytesPerSample == 1) {
            for (row in 0 until rows) {
                val start = row * rowBytes
                for (i in start + samplesPerPixel until start + rowBytes) {
                    if (i >= bytes.size) return
                    bytes[i] = (bytes[i] + bytes[i - samplesPerPixel]).toByte()
                }
            }
            return
        }
        val view = BinaryDataView(bytes)
        for (row in 0 until rows) {
            val start = row * rowBytes
            for (i in samplesPerPixel until width * samplesPerPixel) {
                val at = start + i * bytesPerSample
                val prev = at - samplesPerPixel * bytesPerSample
                if (at + bytesPerSample > bytes.size) return
                when (bytesPerSample) {
                    2 -> view.setUint16(
                        at, (view.getUint16(at, isLittleEndian) + view.getUint16(prev, isLittleEndian)) and 0xFFFF,
                        isLittleEndian
                    )
                    4 -> view.setUint32(
                        at, view.getInt32(at, isLittleEndian) + view.getInt32(prev, isLittleEndian), isLittleEndian
                    )
                }
            }
        }
    }

    /** Undo the floating-point predictor (tag 317 = 3): bytes were de-interleaved per row (all
     *  high-order bytes first, then the next order, …) and then horizontally differenced, which
     *  makes float DEM exponents compress far better. Reverse both steps, in that order. */
    private fun undoFloatingPointPredictor(
        bytes: ByteArray, width: Int, rows: Int, samplesPerPixel: Int, bytesPerSample: Int
    ) {
        val samplesPerRow = width * samplesPerPixel
        val rowBytes = samplesPerRow * bytesPerSample
        val row = ByteArray(rowBytes)
        for (r in 0 until rows) {
            val start = r * rowBytes
            if (start + rowBytes > bytes.size) return
            // Accumulate the byte-wise deltas across the de-interleaved row.
            for (i in start + samplesPerPixel until start + rowBytes) {
                bytes[i] = (bytes[i] + bytes[i - samplesPerPixel]).toByte()
            }
            // Re-interleave: byte b of sample s sits at plane b, position s.
            for (s in 0 until samplesPerRow) {
                for (b in 0 until bytesPerSample) {
                    row[s * bytesPerSample + b] = bytes[start + b * samplesPerRow + s]
                }
            }
            row.copyInto(bytes, start)
        }
    }

    /** Walk the uncompressed block bytes and emit one float per sample. */
    private fun unpackSamples(
        bytes: ByteArray, dir: TiffDirectory, rowsInBlock: Int, bits: Int, isLittleEndian: Boolean
    ): FloatArray {
        val samples = dir.blockWidth * rowsInBlock * dir.samplesPerPixel
        val out = FloatArray(samples)
        val view = BinaryDataView(bytes)
        val bytesPerSample = bits / 8
        val format = dir.sampleFormat.firstOrNull() ?: TiffConstants.SampleFormat.UNSIGNED
        val limit = bytes.size - bytesPerSample
        for (i in 0 until samples) {
            val at = i * bytesPerSample
            if (at > limit) break
            out[i] = when (bits) {
                8 -> if (format == TiffConstants.SampleFormat.SIGNED) view.getInt8(at).toFloat()
                else view.getUint8(at).toFloat()
                16 -> if (format == TiffConstants.SampleFormat.SIGNED) view.getInt16(at, isLittleEndian).toFloat()
                else view.getUint16(at, isLittleEndian).toFloat()
                32 -> when (format) {
                    TiffConstants.SampleFormat.IEEE_FLOAT -> view.getFloat32(at, isLittleEndian)
                    TiffConstants.SampleFormat.SIGNED -> view.getInt32(at, isLittleEndian).toFloat()
                    else -> view.getUint32(at, isLittleEndian).toFloat()
                }
                else -> when (format) {
                    TiffConstants.SampleFormat.IEEE_FLOAT -> view.getFloat64(at, isLittleEndian).toFloat()
                    else -> view.getInt64(at, isLittleEndian).toFloat()
                }
            }
        }
        return out
    }

    private const val PREDICTOR_HORIZONTAL = 2
    private const val PREDICTOR_FLOATING_POINT = 3
    /** A single strip / tile larger than this is treated as a corrupt byte count. */
    private const val MAX_BLOCK_BYTES = 256L * 1024 * 1024
}
