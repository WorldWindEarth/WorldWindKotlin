package earth.worldwind.formats.tiff

import earth.worldwind.formats.tiff.Tiff.Companion.readLimitedDWord
import earth.worldwind.formats.tiff.Tiff.Companion.readWord
import earth.worldwind.formats.tiff.Type.Companion.decode
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import java.nio.ByteBuffer

/**
 * A representation of a Tiff subfile. This class maintains information provided by the Image File Directory and image
 * data. This class is not thread safe.
 */
class Subfile @JvmOverloads constructor(
    /**
     * The parent Tiff which contains this Subfile.
     */
    internal val tiff: Tiff,
    /**
     * The Tiff absolute file offset position of this Subfile.
     */
    internal val offset: Int = 0,
    /**
     * Constructor will not provide parsed default values if true
     */
    skipParsing: Boolean = false
) {
    /**
     * The [Field] associated with this Subfile and provided in the Image File Directory (IFD).
     */
    internal val fields = mutableMapOf<Int, Field>()
    // Minimum Required Tags to support Bi-level and Gray-scale Tiffs
    // 254 - note this is a bit flag not a signed integer type
    internal var newSubfileType = 0
    // 256
    internal var imageWidth = 0
    // 257
    internal var imageLength = 0
    // 258
    internal var bitsPerSample = intArrayOf(1)
    // 259
    internal var compression = 1
    // 262
    internal var photometricInterpretation = 0
    // 277
    internal var samplesPerPixel = 1
    // 282
    internal var xResolution = 0.0
    // 283
    internal var yResolution = 0.0
    // 284
    internal var planarConfiguration = 1
    // 296
    internal var resolutionUnit = 2
    // Strip & Tile Image Data
    // 273
    internal var stripOffsets: IntArray? = null
    // 279
    internal var stripByteCounts: IntArray? = null
    // 278
    internal var rowsPerStrip = -0x1
    // 317
    internal var compressionPredictor = 1
    // 324
    internal var tileOffsets: IntArray? = null
    // 325
    internal var tileByteCounts: IntArray? = null
    // 322
    internal var tileWidth = 0
    // 323
    internal var tileLength = 0
    // 339
    internal var sampleFormat = intArrayOf(Tiff.UNSIGNED_INT)

    init {
        if (!skipParsing) {
            val entries = readWord(tiff.buffer)
            for (i in 0 until entries) {
                val field = Field().apply {
                    subfile = this@Subfile
                    offset = tiff.buffer.position()
                    tag = readWord(tiff.buffer)
                    type = decode(readWord(tiff.buffer))
                    count = readLimitedDWord(tiff.buffer)
                }

                // Check if the data is available in the last four bytes of the field entry or if we need to read the pointer
                val size = field.count * field.type!!.sizeInBytes
                field.dataOffset = if (size > 4) readLimitedDWord(tiff.buffer) else tiff.buffer.position()
                tiff.buffer.position(field.dataOffset)
                field.sliceBuffer(tiff.buffer)
                tiff.buffer.position(field.offset + 12) // move the buffer position to the end of the field
                fields[field.tag] = field
            }
            populateDefinedFields()
        }
    }

    private fun populateDefinedFields() {
        fields[Tiff.NEW_SUBFILE_TYPE_TAG]?.let { newSubfileType = readLimitedDWord(it.getDataBuffer()) }
        fields[Tiff.IMAGE_WIDTH_TAG]?.let { field ->
            imageWidth = when(field.type) {
                Type.USHORT -> readWord(field.getDataBuffer())
                Type.ULONG -> readLimitedDWord(field.getDataBuffer())
                else -> throw RuntimeException(
                    logMessage(
                        ERROR, "Subfile", "populateDefinedFields", "invalid image width type"
                    )
                )
            }
        } ?: throw RuntimeException(
            logMessage(
                ERROR, "Subfile", "populateDefinedFields", "invalid tiff format - image width missing"
            )
        )
        fields[Tiff.IMAGE_LENGTH_TAG]?.let { field ->
            imageLength = when(field.type) {
                Type.USHORT -> readWord(field.getDataBuffer())
                Type.ULONG -> readLimitedDWord(field.getDataBuffer())
                else -> throw RuntimeException(
                    logMessage(
                        ERROR, "Subfile", "populateDefinedFields", "invalid image length type"
                    )
                )
            }
        } ?: throw RuntimeException(
            logMessage(
                ERROR, "Subfile", "populateDefinedFields", "invalid tiff format - image length missing"
            )
        )
        fields[Tiff.BITS_PER_SAMPLE_TAG]?.let { field ->
            bitsPerSample = IntArray(field.count)
            for (i in 0 until field.count) bitsPerSample[i] = readWord(field.getDataBuffer())
        }
        fields[Tiff.COMPRESSION_TAG]?.let { field ->
            compression = readWord(field.getDataBuffer())
            if (compression != 1) throw UnsupportedOperationException(
                logMessage(
                    ERROR, "Subfile", "populateDefineFields", "compressed images are not supported"
                )
            )
        }
        fields[Tiff.PHOTOMETRIC_INTERPRETATION_TAG]?.let { photometricInterpretation = readWord(it.getDataBuffer()) }
            ?: throw RuntimeException(
                logMessage(
                    ERROR, "Subfile", "populatedDefinedFields", "photometric interpretation missing"
                )
            )
        fields[Tiff.SAMPLES_PER_PIXEL_TAG]?.let { samplesPerPixel = readWord(it.getDataBuffer()) }
        fields[Tiff.X_RESOLUTION_TAG]?.let { xResolution = calculateRational(it.getDataBuffer()) }
        fields[Tiff.Y_RESOLUTION_TAG]?.let { yResolution = calculateRational(it.getDataBuffer()) }
        fields[Tiff.PLANAR_CONFIGURATION_TAG]?. let { field ->
            planarConfiguration = readWord(field.getDataBuffer())
            if (planarConfiguration != 1) throw UnsupportedOperationException(
                logMessage(
                    ERROR, "Subfile", "populateDefinedFields",
                    "planar configurations other than 1 are not supported"
                )
            )
        }
        fields[Tiff.RESOLUTION_UNIT_TAG]?.let { resolutionUnit = readWord(it.getDataBuffer()) }
        when {
            fields.containsKey(Tiff.STRIP_OFFSETS_TAG) -> populateStripFields()
            fields.containsKey(Tiff.TILE_OFFSETS_TAG) -> populateTileFields()
            else -> throw RuntimeException(
                logMessage(
                    ERROR, "Subfile", "populateDefinedFields", "no image offsets provided"
                )
            )
        }
        fields[Tiff.COMPRESSION_PREDICTOR_TAG]?.let { compressionPredictor = readWord(it.getDataBuffer()) }
        fields[Tiff.SAMPLE_FORMAT_TAG]?.let { field ->
            sampleFormat = IntArray(field.count)
            for (i in 0 until field.count) sampleFormat[i] = readWord(field.getDataBuffer())
        }
    }

    private fun populateStripFields() {
        fields[Tiff.STRIP_OFFSETS_TAG]?.let { field ->
            val stripOffsets = IntArray(field.count).also { stripOffsets = it }
            val data = field.getDataBuffer()
            for (i in stripOffsets.indices) when(field.type) {
                Type.USHORT -> stripOffsets[i] = readWord(data)
                Type.ULONG -> stripOffsets[i] = readLimitedDWord(data)
                else -> throw RuntimeException(
                    logMessage(
                        ERROR, "Strip", "populateStripFields", "invalid offset type"
                    )
                )
            }
        } ?: throw RuntimeException(
            logMessage(
                ERROR, "Strip", "populateStripFields","invalid tiff format - stripOffsets missing"
            )
        )
        fields[Tiff.ROWS_PER_STRIP_TAG]?.let { field ->
            rowsPerStrip = when(field.type) {
                Type.USHORT -> readWord(field.getDataBuffer())
                Type.ULONG -> readLimitedDWord(field.getDataBuffer())
                else -> throw RuntimeException(
                    logMessage(
                        ERROR, "Strip", "populateStripFields", "invalid rows per strip type"
                    )
                )
            }
        }
        fields[Tiff.STRIP_BYTE_COUNTS_TAG]?.let { field ->
            val stripByteCounts = IntArray(field.count).also { stripByteCounts = it }
            val data = field.getDataBuffer()
            for (i in stripByteCounts.indices) when(field.type) {
                Type.USHORT -> stripByteCounts[i] = readWord(data)
                Type.ULONG -> stripByteCounts[i] = readLimitedDWord(data)
                else -> throw RuntimeException(
                    logMessage(
                        ERROR, "Strip", "populateStripFields", "invalid stripByteCounts type"
                    )
                )
            }
        } ?: throw RuntimeException(
            logMessage(
                ERROR, "Subfile", "populateStripFields", "invalid tiff format - stripByteCounts missing"
            )
        )
    }

    private fun populateTileFields() {
        fields[Tiff.TILE_OFFSETS_TAG]?.let { field ->
            val tileOffsets = IntArray(field.count).also { tileOffsets = it }
            val data = field.getDataBuffer()
            for (i in tileOffsets.indices) when (field.type) {
                Type.USHORT -> tileOffsets[i] = readWord(data)
                Type.ULONG -> tileOffsets[i] = readLimitedDWord(data)
                else -> throw RuntimeException(
                    logMessage(
                        ERROR, "Subfile", "populateTileFields", "invalid offset type"
                    )
                )
            }
        } ?: throw RuntimeException(
            logMessage(ERROR, "Subfile", "populateTileFields", "missing offset")
        )
        fields[Tiff.TILE_BYTE_COUNTS_TAG]?.let { field ->
            val tileByteCounts = IntArray(field.count).also { tileByteCounts = it }
            val data = field.getDataBuffer()
            for (i in tileByteCounts.indices) when(field.type) {
                Type.USHORT -> tileByteCounts[i] = readWord(data)
                Type.ULONG -> tileByteCounts[i] = readLimitedDWord(data)
                else -> throw RuntimeException(
                    logMessage(
                        ERROR, "Subfile", "populateTileFields", "invalid tileByteCounts type"
                    )
                )
            }
        } ?: throw RuntimeException(
            logMessage(
                ERROR, "Subfile", "populateTileFields", "invalid tiff format - tileByteCounts missing"
            )
        )
        fields[Tiff.TILE_WIDTH_TAG]?.let { field ->
            tileWidth = when(field.type) {
                Type.USHORT -> readWord(field.getDataBuffer())
                Type.ULONG -> readLimitedDWord(field.getDataBuffer())
                else -> throw RuntimeException(
                    logMessage(
                        ERROR, "Subfile", "populateTileFields", "invalid tileWidth type"
                    )
                )
            }
        } ?: throw RuntimeException(
            logMessage(
                ERROR, "Subfile", "populateTileFields", "missing tile width field"
            )
        )
        fields[Tiff.TILE_LENGTH_TAG]?.let { field ->
            tileLength = when(field.type) {
                Type.USHORT -> readWord(field.getDataBuffer())
                Type.ULONG -> readLimitedDWord(field.getDataBuffer())
                else -> throw RuntimeException(
                    logMessage(
                        ERROR, "Subfile", "populateTileFields", "invalid tileLength type"
                    )
                )
            }
        } ?: throw RuntimeException(
            logMessage(
                ERROR, "Subfile", "populateTileFields", "missing tileLength field"
            )
        )
    }

    /**
     * Calculates the uncompressed data size. Should be used when preparing the ByteBuffer for write Tiff data in the
     * [getData] method.
     *
     * @return the size in bytes of the uncompressed data
     */
    fun getDataSize(): Int {
        var bytes = 0
        for (i in 0 until samplesPerPixel) bytes += imageLength * imageWidth * bitsPerSample[i] / 8
        return bytes
    }

    /**
     * Writes the uncompressed data from the Tiff data associated with the Subfile to the provided
     * ByteBuffer. The data copied to the provided buffer will use the original data byte order and may override the
     * byte order specified by the provided buffer.
     *
     * @param result a ByteBuffer ready for the uncompressed Tiff data, should have a capacity of at least the return
     * value of [getDataSize]
     *
     * @return the populated provided ByteBuffer
     */
    fun getData(result: ByteBuffer): ByteBuffer {
        require(result.remaining() >= getDataSize()) {
            logMessage(
                ERROR, "Subfile", "getUncompressedImage", "inadequate buffer size"
            )
        }
        // set the result ByteBuffer to our data byte order
        result.order(tiff.buffer.order())
        // TODO handle compression
        if (fields.containsKey(Tiff.STRIP_OFFSETS_TAG)) combineStrips(result) else combineTiles(result)
        return result
    }

    private fun combineStrips(result: ByteBuffer) {
        val stripOffsets = stripOffsets ?: throw RuntimeException("invalid tiff format - stripOffsets missing")
        val stripByteCounts = stripByteCounts ?: throw RuntimeException("invalid tiff format - stripByteCounts missing")
        // this works when the data is not compressed and may work when it is compressed as well
        for (i in stripOffsets.indices) {
            tiff.buffer.limit(stripOffsets[i] + stripByteCounts[i])
            tiff.buffer.position(stripOffsets[i])
            result.put(tiff.buffer)
        }
        tiff.buffer.clear()
    }

    internal fun combineTiles(result: ByteBuffer) {
        val tileOffsets = tileOffsets ?: throw RuntimeException("invalid tiff format - missing offset")
        // this works when the data is not compressed, but it will cause problems if it is compressed and needs to be
        // decompressed as this de-tiles the tiles, each tile should be decompressed prior to this operation
        val tilesAcross = (imageWidth + tileWidth - 1) / tileWidth
        val totalBytesPerSample = getTotalBytesPerPixel()
        for (pixelRow in 0 until imageLength) {
            val currentTileRow = floorDiv(pixelRow, tileLength)
            val tilePixelRow = pixelRow - currentTileRow * tileLength
            for (pixelCol in 0 until imageWidth) {
                val currentTileCol = floorDiv(pixelCol, tileWidth)
                val tileIndex = currentTileRow * tilesAcross + currentTileCol

                // offset byte row/column
                val tilePixelCol = pixelCol - currentTileCol * tileWidth
                val tilePixelIndex = (tilePixelRow * tileWidth + tilePixelCol) * totalBytesPerSample
                val offsetIndex = tileOffsets[tileIndex] + tilePixelIndex
                tiff.buffer.limit(offsetIndex + totalBytesPerSample)
                tiff.buffer.position(offsetIndex)
                result.put(tiff.buffer)
            }
        }
        tiff.buffer.clear()
    }

    private fun getTotalBytesPerPixel(): Int {
        var totalBitsPerSample = 0
        for (i in bitsPerSample.indices) totalBitsPerSample += bitsPerSample[i]
        return totalBitsPerSample / 8
    }

    private fun calculateRational(buffer: ByteBuffer) = Tiff.readDWord(buffer) / Tiff.readDWord(buffer).toDouble()

    companion object {
        /**
         * Borrowed from 1.8 Math package
         *
         * @param x X value
         * @param y Y value
         *
         * @return Result
         */
        private fun floorDiv(x: Int, y: Int): Int {
            var r = x / y
            // if the signs are different and modulo not zero, round down
            if (x xor y < 0 && r * y != x) r--
            return r
        }
    }
}