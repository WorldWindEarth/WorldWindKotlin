package earth.worldwind.formats.geotiff

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * Builds synthetic tiled TIFF / BigTIFF byte arrays so the reader can be exercised without
 * checking binary fixtures into the repo. Covers the axes the production decoder branches
 * on: classic vs BigTIFF headers, overview ladders, sample width and format, compression,
 * and the horizontal predictor.
 */
internal object TiffTestFixtures {
    const val DEFLATE = 32946
    const val PACK_BITS = TiffConstants.Compression.PACK_BITS
    private const val TYPE_LONG8 = 16

    /** One directory entry, already encoded; short payloads ride inline in the IFD. */
    private class Field(val tag: Int, val type: Int, val count: Int, val payload: ByteArray)

    /** One raster in the file — the full-resolution image, or a reduced-resolution overview. */
    private class Raster(
        val width: Int, val height: Int, val tileWidth: Int, val tileHeight: Int,
        val blocks: List<ByteArray>, val isOverview: Boolean,
    )

    /**
     * A tiled raster whose pixel values come from [sample], called with raster coordinates
     * and band index. Coordinates past the image edge are tile padding.
     *
     * [overviewFactors] adds reduced-resolution IFDs decimated by each factor, the way
     * `gdaladdo` builds a pyramid.
     */
    fun tiledTiff(
        width: Int,
        height: Int,
        tileWidth: Int,
        tileHeight: Int,
        samplesPerPixel: Int = 1,
        bitsPerSample: Int = 32,
        sampleFormat: Int = TiffConstants.SampleFormat.IEEE_FLOAT,
        photometric: Int = TiffConstants.PhotometricInterpretation.BLACK_IS_ZERO,
        compression: Int = TiffConstants.Compression.UNCOMPRESSED,
        predictor: Int = 1,
        pixelScale: DoubleArray = doubleArrayOf(0.01, 0.01, 0.0),
        tiePoint: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0, 10.0, 50.0, 0.0),
        bigTiff: Boolean = false,
        bigEndian: Boolean = false,
        noData: String? = null,
        overviewFactors: IntArray = IntArray(0),
        sample: (Int, Int, Int) -> Double,
    ): ByteArray {
        val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val rasters = mutableListOf<Raster>()
        rasters += raster(
            width, height, tileWidth, tileHeight, samplesPerPixel, bitsPerSample, sampleFormat,
            compression, predictor, false, order, sample
        )
        for (factor in overviewFactors) {
            rasters += raster(
                (width + factor - 1) / factor, (height + factor - 1) / factor, tileWidth, tileHeight,
                samplesPerPixel, bitsPerSample, sampleFormat, compression, predictor, true, order
            ) { x, y, band -> sample(x * factor, y * factor, band) }
        }
        return assemble(
            rasters, samplesPerPixel, bitsPerSample, sampleFormat, photometric, compression,
            predictor, pixelScale, tiePoint, bigTiff, order, noData
        )
    }

    private fun raster(
        width: Int, height: Int, tileWidth: Int, tileHeight: Int, samplesPerPixel: Int,
        bitsPerSample: Int, sampleFormat: Int, compression: Int, predictor: Int, isOverview: Boolean,
        order: ByteOrder, sample: (Int, Int, Int) -> Double,
    ): Raster {
        val bytesPerPixel = samplesPerPixel * bitsPerSample / 8
        val tilesAcross = (width + tileWidth - 1) / tileWidth
        val tilesDown = (height + tileHeight - 1) / tileHeight
        val blocks = ArrayList<ByteArray>(tilesAcross * tilesDown)
        for (ty in 0 until tilesDown) for (tx in 0 until tilesAcross) {
            val raw = ByteArray(tileWidth * tileHeight * bytesPerPixel)
            val buffer = ByteBuffer.wrap(raw).order(order)
            for (y in 0 until tileHeight) for (x in 0 until tileWidth) for (band in 0 until samplesPerPixel) {
                writeSample(buffer, sample(tx * tileWidth + x, ty * tileHeight + y, band), bitsPerSample, sampleFormat)
            }
            if (predictor == 2) applyHorizontalDifference(raw, tileWidth, tileHeight, samplesPerPixel, bitsPerSample / 8, order)
            blocks.add(compress(raw, compression))
        }
        return Raster(width, height, tileWidth, tileHeight, blocks, isOverview)
    }

    private fun writeSample(buffer: ByteBuffer, value: Double, bits: Int, format: Int) {
        when {
            format == TiffConstants.SampleFormat.IEEE_FLOAT && bits == 32 -> buffer.putFloat(value.toFloat())
            format == TiffConstants.SampleFormat.IEEE_FLOAT && bits == 64 -> buffer.putDouble(value)
            bits == 8 -> buffer.put(value.toInt().toByte())
            bits == 16 -> buffer.putShort(value.toInt().toShort())
            else -> buffer.putInt(value.toInt())
        }
    }

    /** Encoder side of TIFF predictor 2: store each sample as its delta from the previous
     *  sample of the same band in the row, which is what the decoder has to undo. */
    private fun applyHorizontalDifference(
        bytes: ByteArray, width: Int, rows: Int, samplesPerPixel: Int, bytesPerSample: Int, order: ByteOrder
    ) {
        val rowBytes = width * samplesPerPixel * bytesPerSample
        val buffer = ByteBuffer.wrap(bytes).order(order)
        for (row in 0 until rows) {
            val start = row * rowBytes
            // Walk backwards so each delta is taken against the still-absolute previous sample.
            for (i in width * samplesPerPixel - 1 downTo samplesPerPixel) {
                val at = start + i * bytesPerSample
                val prev = at - samplesPerPixel * bytesPerSample
                when (bytesPerSample) {
                    1 -> bytes[at] = (bytes[at] - bytes[prev]).toByte()
                    2 -> buffer.putShort(at, (buffer.getShort(at) - buffer.getShort(prev)).toShort())
                    4 -> buffer.putInt(at, buffer.getInt(at) - buffer.getInt(prev))
                }
            }
        }
    }

    private fun compress(raw: ByteArray, compression: Int) = when (compression) {
        TiffConstants.Compression.UNCOMPRESSED -> raw
        DEFLATE -> deflate(raw)
        PACK_BITS -> packBits(raw)
        else -> throw IllegalArgumentException("Test fixture cannot write compression $compression")
    }

    fun deflate(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    /** Minimal PackBits encoder: literal runs only, which is a valid encoding of any input. */
    private fun packBits(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var at = 0
        while (at < raw.size) {
            val n = minOf(128, raw.size - at)
            out.write(n - 1)
            out.write(raw, at, n)
            at += n
        }
        return out.toByteArray()
    }

    private fun assemble(
        rasters: List<Raster>, samplesPerPixel: Int, bitsPerSample: Int, sampleFormat: Int,
        photometric: Int, compression: Int, predictor: Int, pixelScale: DoubleArray,
        tiePoint: DoubleArray, bigTiff: Boolean, order: ByteOrder, noData: String?,
    ): ByteArray {
        val headerSize = if (bigTiff) 16 else 8
        val inlineSize = if (bigTiff) 8 else 4
        val entrySize = if (bigTiff) 20 else 12
        val ifdHeaderSize = if (bigTiff) 8 else 2
        val offsetType = if (bigTiff) TYPE_LONG8 else TiffConstants.Type.LONG
        val offsetUnit = if (bigTiff) 8 else 4

        // Raster bytes first, then out-of-line tag values, then the chained IFDs.
        var at = headerSize.toLong()
        val blockOffsets = rasters.map { raster ->
            LongArray(raster.blocks.size) { i ->
                val offset = at
                at += raster.blocks[i].size
                offset
            }
        }

        val fieldsPerRaster = rasters.mapIndexed { index, raster ->
            val fields = mutableListOf<Field>()
            if (raster.isOverview) fields += Field(
                TiffConstants.IFDTag.NEW_SUBFILE_TYPE, TiffConstants.Type.LONG, 1, ints(order, intArrayOf(1), 4)
            )
            fields += Field(TiffConstants.IFDTag.IMAGE_WIDTH, TiffConstants.Type.LONG, 1, ints(order, intArrayOf(raster.width), 4))
            fields += Field(TiffConstants.IFDTag.IMAGE_LENGTH, TiffConstants.Type.LONG, 1, ints(order, intArrayOf(raster.height), 4))
            fields += Field(
                TiffConstants.IFDTag.BITS_PER_SAMPLE, TiffConstants.Type.SHORT, samplesPerPixel,
                ints(order, IntArray(samplesPerPixel) { bitsPerSample }, 2)
            )
            fields += Field(TiffConstants.IFDTag.COMPRESSION, TiffConstants.Type.SHORT, 1, ints(order, intArrayOf(compression), 2))
            fields += Field(
                TiffConstants.IFDTag.PHOTOMETRIC_INTERPRETATION, TiffConstants.Type.SHORT, 1, ints(order, intArrayOf(photometric), 2)
            )
            fields += Field(
                TiffConstants.IFDTag.SAMPLES_PER_PIXEL, TiffConstants.Type.SHORT, 1, ints(order, intArrayOf(samplesPerPixel), 2)
            )
            fields += Field(TiffConstants.IFDTag.PLANAR_CONFIGURATION, TiffConstants.Type.SHORT, 1, ints(order, intArrayOf(1), 2))
            fields += Field(TiffConstants.IFDTag.PREDICTOR, TiffConstants.Type.SHORT, 1, ints(order, intArrayOf(predictor), 2))
            fields += Field(TiffConstants.IFDTag.TILE_WIDTH, TiffConstants.Type.LONG, 1, ints(order, intArrayOf(raster.tileWidth), 4))
            fields += Field(TiffConstants.IFDTag.TILE_LENGTH, TiffConstants.Type.LONG, 1, ints(order, intArrayOf(raster.tileHeight), 4))
            fields += Field(
                TiffConstants.IFDTag.TILE_OFFSETS, offsetType, raster.blocks.size,
                longs(order, blockOffsets[index], offsetUnit)
            )
            fields += Field(
                TiffConstants.IFDTag.TILE_BYTE_COUNTS, offsetType, raster.blocks.size,
                longs(order, LongArray(raster.blocks.size) { raster.blocks[it].size.toLong() }, offsetUnit)
            )
            fields += Field(
                TiffConstants.IFDTag.SAMPLE_FORMAT, TiffConstants.Type.SHORT, samplesPerPixel,
                ints(order, IntArray(samplesPerPixel) { sampleFormat }, 2)
            )
            if (index == 0) {
                noData?.let {
                    val text = it.toByteArray(Charsets.US_ASCII) + 0
                    fields += Field(GeoTiffConstants.GDAL_NODATA, TiffConstants.Type.ASCII, text.size, text)
                }
                fields += Field(
                    GeoTiffConstants.MODEL_PIXEL_SCALE, TiffConstants.Type.DOUBLE, pixelScale.size, doubles(order, pixelScale)
                )
                fields += Field(GeoTiffConstants.MODEL_TIEPOINT, TiffConstants.Type.DOUBLE, tiePoint.size, doubles(order, tiePoint))
                // GeoKeys: geographic model, pixel-is-area, WGS 84.
                val geoKeys = intArrayOf(1, 1, 0, 3, 1024, 0, 1, 2, 1025, 0, 1, 1, 2048, 0, 1, 4326)
                fields += Field(GeoTiffConstants.GEO_KEY_DIRECTORY, TiffConstants.Type.SHORT, geoKeys.size, ints(order, geoKeys, 2))
            }
            fields.sortBy { it.tag }
            fields
        }

        val external = LinkedHashMap<Field, Long>()
        var externalAt = at
        for (fields in fieldsPerRaster) for (field in fields) if (field.payload.size > inlineSize) {
            external[field] = externalAt
            externalAt += field.payload.size + field.payload.size % 2 // keep word alignment
        }

        val ifdOffsets = LongArray(rasters.size)
        var ifdAt = externalAt
        for (i in rasters.indices) {
            ifdOffsets[i] = ifdAt
            ifdAt += ifdHeaderSize + fieldsPerRaster[i].size * entrySize + offsetUnit
        }

        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(headerSize).order(order)
        header.putShort(if (order == ByteOrder.BIG_ENDIAN) 0x4D4D else 0x4949)
        if (bigTiff) {
            header.putShort(43)
            header.putShort(8)
            header.putShort(0)
            header.putLong(ifdOffsets[0])
        } else {
            header.putShort(42)
            header.putInt(ifdOffsets[0].toInt())
        }
        out.write(header.array())
        for (raster in rasters) for (block in raster.blocks) out.write(block)
        for ((field, offset) in external) {
            check(out.size().toLong() == offset) { "External value misplaced for tag ${field.tag}" }
            out.write(field.payload)
            if (field.payload.size % 2 != 0) out.write(0)
        }
        for (i in rasters.indices) {
            check(out.size().toLong() == ifdOffsets[i]) { "IFD $i misplaced" }
            val fields = fieldsPerRaster[i]
            val ifd = ByteBuffer.allocate(ifdHeaderSize + fields.size * entrySize + offsetUnit)
                .order(order)
            if (bigTiff) ifd.putLong(fields.size.toLong()) else ifd.putShort(fields.size.toShort())
            for (field in fields) {
                ifd.putShort(field.tag.toShort())
                ifd.putShort(field.type.toShort())
                if (bigTiff) ifd.putLong(field.count.toLong()) else ifd.putInt(field.count)
                val offset = external[field]
                if (offset != null) {
                    if (bigTiff) ifd.putLong(offset) else ifd.putInt(offset.toInt())
                } else {
                    ifd.put(field.payload.copyOf(inlineSize))
                }
            }
            val next = if (i + 1 < rasters.size) ifdOffsets[i + 1] else 0L
            if (bigTiff) ifd.putLong(next) else ifd.putInt(next.toInt())
            out.write(ifd.array(), 0, ifd.position())
        }
        return out.toByteArray()
    }

    private fun ints(order: ByteOrder, values: IntArray, unit: Int): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * unit).order(order)
        for (value in values) if (unit == 2) buffer.putShort(value.toShort()) else buffer.putInt(value)
        return buffer.array()
    }

    private fun longs(order: ByteOrder, values: LongArray, unit: Int): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * unit).order(order)
        for (value in values) if (unit == 8) buffer.putLong(value) else buffer.putInt(value.toInt())
        return buffer.array()
    }

    private fun doubles(order: ByteOrder, values: DoubleArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 8).order(order)
        for (value in values) buffer.putDouble(value)
        return buffer.array()
    }
}
