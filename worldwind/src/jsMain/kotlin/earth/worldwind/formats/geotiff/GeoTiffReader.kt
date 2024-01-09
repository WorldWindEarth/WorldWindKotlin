package earth.worldwind.formats.geotiff

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.INFO
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log
import earth.worldwind.util.Logger.logMessage
import org.khronos.webgl.*
import kotlin.math.ceil

class GeoTiffReader(arrayBuffer: ArrayBuffer) {
    private val imageFileDirectories = mutableListOf<TiffIFDEntry>()
    private val geoTiffData = DataView(arrayBuffer)
    private val metadata = GeoTiffMetadata()
    private var isLittleEndian = false

    init {
        getEndianness()
        require(isTiffFileType()) { logMessage(ERROR, "GeoTiffReader", "constructor","Invalid file type") }
        parseImageFileDirectory(GeoTiffUtil.getBytes(geoTiffData, 4, 4, isLittleEndian).toInt())
        getMetadataFromImageFileDirectory()
        parseGeoKeys()
        setBBox()
    }

    private fun getEndianness() {
        isLittleEndian = when (GeoTiffUtil.getBytes(geoTiffData, 0, 2, isLittleEndian)) {
            0x4949 -> true
            0x4D4D -> false
            else -> error(logMessage(ERROR, "GeoTiffReader", "getEndianness","Invalid Byte Order Value"))
        }
    }

    private fun isTiffFileType() = GeoTiffUtil.getBytes(geoTiffData, 2, 2, isLittleEndian) == 42

    private fun isGeoTiff() = getIFDByTag(GeoTiffConstants.GEO_KEY_DIRECTORY) != null

    fun createTypedElevationArray(): ArrayBufferView {
        val elevations = mutableListOf<Number>()
        if (metadata.stripOffsets.isNotEmpty()) {
            elevations.addAll(parseStrips(true).flatten())
        } else if (metadata.tileOffsets.isNotEmpty()) {
            val tiles = parseTiles(true)
            val tilesAcross = (metadata.imageWidth + metadata.tileWidth - 1) / metadata.tileWidth

            for (y in 0 until metadata.imageLength) for (x in 0 until metadata.imageWidth) {
                val tileAcross = x / metadata.tileWidth
                val tileDown = y / metadata.tileLength
                val tileIndex = tileDown * tilesAcross + tileAcross
                val xInTile = x % metadata.tileWidth
                val yInTile = y % metadata.tileLength
                val sampleIndex = yInTile * metadata.tileWidth + xInTile
                val pixelSamples = tiles[tileIndex][sampleIndex]
                elevations.add(pixelSamples)
            }
        } else error(logMessage(ERROR, "GeoTiffReader", "createTypedElevationArray","Invalid metadata"))

        return when(metadata.bitsPerSample[0]) {
            8 -> when (metadata.sampleFormat) {
                TiffConstants.SampleFormat.SIGNED -> Int8Array(elevations.toTypedArray().unsafeCast<Array<Byte>>())
                else -> Uint8Array(elevations.toTypedArray().unsafeCast<Array<Byte>>())
            }
            16 -> when (metadata.sampleFormat) {
                TiffConstants.SampleFormat.SIGNED -> Int16Array(elevations.toTypedArray().unsafeCast<Array<Short>>())
                else -> Uint16Array(elevations.toTypedArray().unsafeCast<Array<Short>>())
            }
            32 -> when (metadata.sampleFormat) {
                TiffConstants.SampleFormat.SIGNED -> Int32Array(elevations.toTypedArray().unsafeCast<Array<Int>>())
                TiffConstants.SampleFormat.IEEE_FLOAT -> Float32Array(elevations.toTypedArray().unsafeCast<Array<Float>>())
                else -> Uint32Array(elevations.toTypedArray().unsafeCast<Array<Int>>())
            }
            64 -> Float64Array(elevations.toTypedArray().unsafeCast<Array<Double>>())
            else -> error(logMessage(ERROR, "GeoTiffReader", "createTypedElevationArray","Invalid bits per sample"))
        }
    }

    private fun parseStrips(returnElevation: Boolean): Array<Array<Number>> {
        val bytesPerPixel = metadata.samplesPerPixel * metadata.bitsPerSample[0] / 8
        return Array(metadata.stripOffsets.size) {
            parseBlock(
                returnElevation, metadata.compression, bytesPerPixel, metadata.stripByteCounts[it],
                metadata.stripOffsets[it], metadata.bitsPerSample, metadata.sampleFormat
            )
        }
    }

    private fun parseBlock(
        returnElevation: Boolean, compression: Int, bytesPerPixel: Int, blockByteCount: Int, blockOffset: Int,
        bitsPerSample: List<Int>, sampleFormat: Int
    ) = when (compression) {
        TiffConstants.Compression.UNCOMPRESSED ->
            parseUncompressedBlock(blockByteCount, bitsPerSample, blockOffset, sampleFormat, returnElevation, bytesPerPixel)
        TiffConstants.Compression.PACK_BITS ->
            parsePackBitsCompressedBlocks(bytesPerPixel, blockByteCount, blockOffset, bitsPerSample, sampleFormat, returnElevation)
        TiffConstants.Compression.CCITT_1D -> {
            log(WARN, "Compression type not yet implemented: CCITT_1D")
            emptyArray()
        }
        TiffConstants.Compression.GROUP_3_FAX -> {
            log(WARN, "Compression type not yet implemented: GROUP_3_FAX")
            emptyArray()
        }
        TiffConstants.Compression.GROUP_4_FAX -> {
            log(WARN, "Compression type not yet implemented: GROUP_4_FAX")
            emptyArray()
        }
        TiffConstants.Compression.LZW -> {
            log(WARN, "Compression type not yet implemented: LZW")
            emptyArray()
        }
        TiffConstants.Compression.JPEG -> {
            log(WARN, "Compression type not yet implemented: JPEG")
            emptyArray()
        }
        else -> {
            log(WARN, "Unsupported compression type $compression")
            emptyArray()
        }
    }

    private fun parsePackBitsCompressedBlocks(
        bytesPerPixel: Int, blockByteCount: Int, blockOffset: Int, bitsPerSample: List<Int>, sampleFormat: Int,
        returnElevation: Boolean
    ): Array<Number> {
        val block = mutableListOf<Number>()
        val arrayBuffer = if (metadata.tileWidth != 0 && metadata.tileLength != 0) {
            Int8Array(metadata.tileWidth * metadata.tileLength * bytesPerPixel)
        } else {
            Int8Array(metadata.rowsPerStrip * metadata.imageWidth * bytesPerPixel)
        }
        val uncompressedDataView = DataView(arrayBuffer.buffer)
        var newBlock = true
        var blockLength = 0
        var numOfIterations = 0
        var uncompressedOffset = 0

        for (byteOffset in 0 until blockByteCount) {
            if (newBlock) {
                blockLength = 1
                numOfIterations = 1
                newBlock = false
                when (val nextSourceByte = geoTiffData.getInt8(blockOffset + byteOffset)) {
                    in 0..127 -> blockLength = nextSourceByte + 1
                    in -127..-1 -> numOfIterations = -nextSourceByte + 1
                    else -> newBlock = true
                }
            } else {
                val currentByte = GeoTiffUtil.getBytes(geoTiffData, blockOffset + byteOffset, 1, isLittleEndian)
                for (currentIteration in 0 until numOfIterations) {
                    uncompressedDataView.setInt8(uncompressedOffset, currentByte.toByte())
                    uncompressedOffset++
                }
                blockLength--
                if (blockLength == 0) newBlock = true
            }
        }

        for (byteOffset in 0 until arrayBuffer.length step bytesPerPixel) {
            val pixel = mutableListOf<Number>()
            for ((i, sample) in bitsPerSample.withIndex()) {
                val bytesPerSample = sample / 8
                val sampleOffset = i * bytesPerSample
                pixel.add(GeoTiffUtil.getSampleBytes(
                    uncompressedDataView, byteOffset + sampleOffset, bytesPerSample, sampleFormat, isLittleEndian
                ))
            }
            if (returnElevation) block.add(pixel[0]) else block.addAll(pixel)
        }
        return block.toTypedArray()
    }

    private fun parseUncompressedBlock(
        blockByteCount: Int, bitsPerSample: List<Int>, blockOffset: Int, sampleFormat: Int, returnElevation: Boolean,
        bytesPerPixel: Int
    ): Array<Number> {
        val block = mutableListOf<Number>()
        var byteOffset = 0
        while (byteOffset < blockByteCount) {
            val pixel = mutableListOf<Number>()
            for ((m, sample) in bitsPerSample.withIndex()) {
                val bytesPerSample = sample / 8
                val sampleOffset = m * bytesPerSample
                pixel.add(GeoTiffUtil.getSampleBytes(
                    geoTiffData, blockOffset + byteOffset + sampleOffset, bytesPerSample, sampleFormat, isLittleEndian
                ))
            }
            if (returnElevation) block.add(pixel[0])
            else block.addAll(pixel.toList())
            byteOffset += bytesPerPixel
        }
        return block.toTypedArray()
    }

    private fun parseTiles(returnElevation: Boolean): Array<Array<Number>> {
        val bytesPerPixel = metadata.samplesPerPixel * metadata.bitsPerSample[0] / 8
        val tilesAcross = ceil(metadata.imageWidth / metadata.tileWidth.toDouble()).toInt()
        val tilesDown = ceil(metadata.imageLength / metadata.tileLength.toDouble()).toInt()
        return Array(tilesDown * tilesAcross) {
            parseBlock(
                returnElevation, metadata.compression, bytesPerPixel, metadata.tileByteCounts[it],
                metadata.tileOffsets[it], metadata.bitsPerSample, metadata.sampleFormat
            )
        }
    }

    private fun geoTiffImageToPCS(xValue: Double, yValue: Double) = when {
        metadata.modelTiePoint.size > 6 && metadata.modelPixelScale.isEmpty() -> TODO()
        metadata.modelTransformation.size == 16 -> Location(
            Angle.fromDegrees(yValue * metadata.modelTransformation[4] + yValue * metadata.modelTransformation[5]
                    + metadata.modelTransformation[7]),
            Angle.fromDegrees(xValue * metadata.modelTransformation[0] + yValue * metadata.modelTransformation[1]
                    + metadata.modelTransformation[3])
        )
        metadata.modelPixelScale.size < 3 || metadata.modelTiePoint.size < 6 -> Location(Angle.fromDegrees(yValue), Angle.fromDegrees(xValue))
        else -> Location(
            Angle.fromDegrees((yValue - metadata.modelTiePoint[1]) * -1 * metadata.modelPixelScale[1] + metadata.modelTiePoint[4]),
            Angle.fromDegrees((xValue - metadata.modelTiePoint[0]) * metadata.modelPixelScale[0] + metadata.modelTiePoint[3])
        )
    }

    private fun setBBox() {
        val upperLeft = geoTiffImageToPCS(0.0, 0.0)
        val upperRight = geoTiffImageToPCS(metadata.imageWidth.toDouble(), 0.0)
        val lowerLeft = geoTiffImageToPCS(0.0, metadata.imageLength.toDouble())
        metadata.bbox = Sector(lowerLeft.latitude, upperLeft.latitude, upperLeft.longitude, upperRight.longitude)
    }

    private fun getMetadataFromImageFileDirectory() {
        for (ifd in imageFileDirectories) when (ifd.tag) {
            TiffConstants.IFDTag.BITS_PER_SAMPLE -> metadata.bitsPerSample = ifd.getIFDEntryValue().unsafeCast<List<Int>>()
            TiffConstants.IFDTag.COLOR_MAP -> metadata.colorMap = ifd.getIFDEntryValue().toTypedArray()
            TiffConstants.IFDTag.COMPRESSION -> metadata.compression = ifd.getIFDEntryValue()[0].toInt()
            TiffConstants.IFDTag.EXTRA_SAMPLES -> metadata.extraSamples = ifd.getIFDEntryValue().toTypedArray()
            TiffConstants.IFDTag.IMAGE_DESCRIPTION -> metadata.imageDescription = ifd.getIFDEntryAsciiValue()
            TiffConstants.IFDTag.IMAGE_LENGTH -> metadata.imageLength = ifd.getIFDEntryValue()[0].toInt()
            TiffConstants.IFDTag.IMAGE_WIDTH -> metadata.imageWidth = ifd.getIFDEntryValue()[0].toInt()
            TiffConstants.IFDTag.MAX_SAMPLE_VALUE -> metadata.maxSampleValue = ifd.getIFDEntryValue()[0]
            TiffConstants.IFDTag.MIN_SAMPLE_VALUE -> metadata.minSampleValue = ifd.getIFDEntryValue()[0]
            TiffConstants.IFDTag.ORIENTATION -> metadata.orientation = ifd.getIFDEntryValue()[0].toInt()
            TiffConstants.IFDTag.PHOTOMETRIC_INTERPRETATION -> metadata.photometricInterpretation = ifd.getIFDEntryValue()[0].toInt()
            TiffConstants.IFDTag.PLANAR_CONFIGURATION -> metadata.planarConfiguration = ifd.getIFDEntryValue()[0].toInt()
            TiffConstants.IFDTag.ROWS_PER_STRIP -> metadata.rowsPerStrip = ifd.getIFDEntryValue()[0].toInt()
            TiffConstants.IFDTag.RESOLUTION_UNIT -> metadata.resolutionUnit = ifd.getIFDEntryValue()[0]
            TiffConstants.IFDTag.SAMPLES_PER_PIXEL -> metadata.samplesPerPixel = ifd.getIFDEntryValue()[0].toInt()
            TiffConstants.IFDTag.SAMPLE_FORMAT -> metadata.sampleFormat = ifd.getIFDEntryValue()[0].toInt()
            TiffConstants.IFDTag.SOFTWARE -> metadata.software = ifd.getIFDEntryValue().toTypedArray()
            TiffConstants.IFDTag.STRIP_BYTE_COUNTS -> metadata.stripByteCounts = ifd.getIFDEntryValue().unsafeCast<List<Int>>()
            TiffConstants.IFDTag.STRIP_OFFSETS -> metadata.stripOffsets = ifd.getIFDEntryValue().unsafeCast<List<Int>>()
            TiffConstants.IFDTag.TILE_BYTE_COUNTS -> metadata.tileByteCounts = ifd.getIFDEntryValue().unsafeCast<List<Int>>()
            TiffConstants.IFDTag.TILE_OFFSETS -> metadata.tileOffsets = ifd.getIFDEntryValue().unsafeCast<List<Int>>()
            TiffConstants.IFDTag.TILE_LENGTH -> metadata.tileLength = ifd.getIFDEntryValue()[0].toInt()
            TiffConstants.IFDTag.TILE_WIDTH -> metadata.tileWidth = ifd.getIFDEntryValue()[0].toInt()
            TiffConstants.IFDTag.X_RESOLUTION -> metadata.xResolution = ifd.getIFDEntryValue()[0]
            TiffConstants.IFDTag.Y_RESOLUTION -> metadata.yResolution = ifd.getIFDEntryValue()[0]
            GeoTiffConstants.GEO_ASCII_PARAMS -> metadata.geoAsciiParams = ifd.getIFDEntryAsciiValue()
            GeoTiffConstants.GEO_DOUBLE_PARAMS -> metadata.geoDoubleParams = ifd.getIFDEntryValue().unsafeCast<List<Double>>()
            GeoTiffConstants.GEO_KEY_DIRECTORY -> metadata.geoKeyDirectory = ifd.getIFDEntryValue().unsafeCast<List<Int>>()
            GeoTiffConstants.MODEL_PIXEL_SCALE ->  metadata.modelPixelScale = ifd.getIFDEntryValue().unsafeCast<List<Double>>()
            GeoTiffConstants.MODEL_TIEPOINT -> metadata.modelTiePoint = ifd.getIFDEntryValue().unsafeCast<List<Double>>()
            GeoTiffConstants.GDAL_METADATA -> metadata.metaData = ifd.getIFDEntryAsciiValue()
            GeoTiffConstants.GDAL_NODATA -> metadata.noData = ifd.getIFDEntryValue()[0]
            else -> log(WARN, "Ignored GeoTiff tag: ${ifd.tag}")
        }
    }

    private fun parseGeoKeys() {
        require(isGeoTiff()) { logMessage(Logger.INFO, "GeoTiffReader", "parse", "Invalid GeoTiff file") }
        val geoKeyDirectory = metadata.geoKeyDirectory
        if (geoKeyDirectory.isNotEmpty()) {
            val numberOfKeys = geoKeyDirectory[3]

            for (i in 0 until numberOfKeys) {
                val keyId = geoKeyDirectory[4 + i * 4]
                val tiffTagLocation = geoKeyDirectory[5 + i * 4]
                val count = geoKeyDirectory[6 + i * 4]
                val valueOffset = geoKeyDirectory[7 + i * 4]

                when (keyId) {
                    GeoTiffConstants.GT_MODEL_TYPE_GEO_KEY ->
                        metadata.gtModelTypeGeoKey = GeoTiffKeyEntry(keyId, tiffTagLocation, count,valueOffset)
                            .getGeoKeyValue(metadata.geoDoubleParams)
                    GeoTiffConstants.GT_RASTER_TYPE_GEO_KEY ->
                        metadata.gtRasterTypeGeoKey = GeoTiffKeyEntry(keyId, tiffTagLocation, count, valueOffset)
                            .getGeoKeyValue(metadata.geoDoubleParams)
                    GeoTiffConstants.GT_CITATION_GEO_KEY ->
                        metadata.gtCitationGeoKey = GeoTiffKeyEntry(keyId, tiffTagLocation, count, valueOffset)
                            .getAsciiGeoKeyValue(metadata.geoAsciiParams)
                    GeoTiffConstants.GEOGRAPHIC_TYPE_GEO_KEY ->
                        metadata.geographicTypeGeoKey = GeoTiffKeyEntry(keyId, tiffTagLocation, count, valueOffset)
                            .getGeoKeyValue(metadata.geoDoubleParams)
                    GeoTiffConstants.GEOG_CITATION_GEO_KEY ->
                        metadata.geogCitationGeoKey = GeoTiffKeyEntry(keyId, tiffTagLocation, count, valueOffset)
                            .getAsciiGeoKeyValue(metadata.geoAsciiParams)
                    GeoTiffConstants.GEOG_ANGULAR_UNITS_GEO_KEY ->
                        metadata.geogAngularUnitsGeoKey = GeoTiffKeyEntry(keyId, tiffTagLocation, count, valueOffset)
                            .getGeoKeyValue(metadata.geoDoubleParams)
                    GeoTiffConstants.GEOG_ANGULAR_UNIT_SIZE_GEO_KEY ->
                        metadata.geogAngularUnitSizeGeoKey = GeoTiffKeyEntry(keyId, tiffTagLocation, count, valueOffset )
                            .getGeoKeyValue(metadata.geoDoubleParams)
                    GeoTiffConstants.GEOG_SEMI_MAJOR_AXIS_GEO_KEY ->
                        metadata.geogSemiMajorAxisGeoKey = GeoTiffKeyEntry(keyId, tiffTagLocation, count, valueOffset)
                            .getGeoKeyValue(metadata.geoDoubleParams)
                    GeoTiffConstants.GEOG_INV_FLATTENING_GEO_KEY ->
                        metadata.geogInvFlatteningGeoKey = GeoTiffKeyEntry(keyId, tiffTagLocation, count, valueOffset)
                            .getGeoKeyValue(metadata.geoDoubleParams)
                    GeoTiffConstants.PROJECTED_CS_TYPE_GEO_KEY ->
                        metadata.projectedCSType = GeoTiffKeyEntry(keyId, tiffTagLocation, count, valueOffset)
                            .getGeoKeyValue(metadata.geoDoubleParams)
                    GeoTiffConstants.PROJ_LINEAR_UNITS_GEO_KEY ->
                        metadata.projLinearUnits = GeoTiffKeyEntry(keyId, tiffTagLocation, count, valueOffset)
                            .getGeoKeyValue(metadata.geoDoubleParams)
                    else -> log(WARN, "Ignored GeoTiff key: $keyId")
                }
            }
        } else error(logMessage(INFO, "GeoTiffReader", "parseGeoKeys", "missingGeoKeyDirectoryTag"))
    }

    private fun parseImageFileDirectory(offset: Int) {
        val noOfDirectoryEntries = GeoTiffUtil.getBytes(geoTiffData, offset, 2, isLittleEndian).toInt()
        val directoryEntries = mutableListOf<TiffIFDEntry>()
        var i = offset + 2
        repeat(noOfDirectoryEntries) {
            val tag = GeoTiffUtil.getBytes(geoTiffData, i, 2, isLittleEndian).toInt()
            val type = GeoTiffUtil.getBytes(geoTiffData, i + 2, 2, isLittleEndian).toInt()
            val count = GeoTiffUtil.getBytes(geoTiffData, i + 4, 4, isLittleEndian).toInt()
            val valueOffset = GeoTiffUtil.getBytes(geoTiffData, i + 8, 4, isLittleEndian).toInt()
            directoryEntries.add(TiffIFDEntry(tag, type, count, valueOffset, geoTiffData, isLittleEndian))
            i += 12
        }
        imageFileDirectories.addAll(directoryEntries)
        val nextIfdOffset = GeoTiffUtil.getBytes(geoTiffData, i, 4, isLittleEndian).toInt()
        if (nextIfdOffset != 0) parseImageFileDirectory(nextIfdOffset)
    }

    // Get image file directory by tag value.
    private fun getIFDByTag(tag: Int) = imageFileDirectories.find { it.tag == tag }
}