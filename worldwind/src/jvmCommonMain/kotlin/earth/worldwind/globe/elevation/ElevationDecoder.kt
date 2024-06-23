package earth.worldwind.globe.elevation

import ar.com.hjg.pngj.ImageInfo
import ar.com.hjg.pngj.ImageLineInt
import ar.com.hjg.pngj.PngReaderInt
import ar.com.hjg.pngj.PngWriter
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.ResourcePostprocessor
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mil.nga.tiff.*
import mil.nga.tiff.util.TiffConstants.*
import java.io.*
import java.net.URL
import java.nio.*
import kotlin.math.roundToInt

open class ElevationDecoder: Closeable {
    protected val httpClient = DefaultHttpClient()

    override fun close() = httpClient.close()

    open suspend fun decodeElevation(elevationSource: ElevationSource) = withContext(Dispatchers.IO) {
        decodeBuffer(when {
            elevationSource.isElevationDataFactory -> elevationSource.asElevationDataFactory().fetchElevationData()
            elevationSource.isFile -> decodeFile(elevationSource.asFile(), elevationSource.postprocessor)
            elevationSource.isUrl -> decodeUrl(elevationSource.asUrl(), elevationSource.postprocessor)
            else -> decodeUnrecognized(elevationSource)
        })
    }

    protected open fun decodeBuffer(buffer: Buffer?) = when (buffer) {
        is ShortBuffer -> ShortArray(buffer.remaining()).also { buffer.get(it) }
        is FloatBuffer -> ShortArray(buffer.remaining()) {
            val value = buffer.get()
            // Consider converting null value from float to short
            if (value == Float.MAX_VALUE) Short.MIN_VALUE else value.roundToInt().toShort()
        }
        else -> null
    }

    protected open suspend fun decodeFile(file: File, postprocessor: ResourcePostprocessor?) =
        when(file.name.substring(file.name.lastIndexOf('.') + 1).lowercase()) {
            "png" -> "image/png"
            "tiff" -> "image/tiff"
            "bil16" -> "application/bil16"
            "bil32" -> "application/bil32"
            else -> null
        }?.let { decodeBytes(FileInputStream(file).readBytes(), it, postprocessor) } ?: error(
            logMessage(ERROR, "ElevationDecoder", "decodeFile", "Unknown mime-type")
        )

    protected open suspend fun decodeUrl(url: URL, postprocessor: ResourcePostprocessor?) = httpClient.get(url).let {
        if (it.status == HttpStatusCode.OK) {
            decodeBytes(it.readBytes(), it.contentType().toString(), postprocessor)
        } else null // The result is not an elevation data, access denied or server error
    }

    protected open fun decodeUnrecognized(imageSource: ElevationSource): Buffer? {
        log(WARN, "Unrecognized image source '$imageSource'")
        return null
    }

    protected open suspend fun decodeBytes(bytes: ByteArray, contentType: String?, postprocessor: ResourcePostprocessor?) = when {
        contentType.equals("image/bil", true) ||
        contentType.equals("application/bil", true) ||
        contentType.equals("application/bil16", true) -> wrapBytes(bytes).asShortBuffer()
        contentType.equals("application/bil32", true) -> wrapBytes(bytes).asFloatBuffer()
        contentType.equals("image/tiff", true) -> decodeTiff(bytes)
        contentType.equals("image/png", true) -> decodePng(bytes)
        else -> throw RuntimeException(
            logMessage(ERROR, "ElevationDecoder", "decodeBytes", "Format not supported: $contentType")
        )
    }.let { postprocessor?.process(it) ?: it } // Process loaded elevation data if necessary

    open fun decodeTiff(bytes: ByteArray): Buffer {
        val directory = TiffReader.readTiff(bytes).fileDirectory
        return when {
            isInt16Tiff(directory) -> directory.readRasters().sampleValues[0].asShortBuffer()
            isFloat32Tiff(directory) -> directory.readRasters().sampleValues[0].asFloatBuffer()
            else -> error(
                logMessage(
                    ERROR, "ElevationDecoder", "decodeTiff", "Tiff file format is not supported"
                )
            )
        }
    }

    protected open fun isInt16Tiff(directory: FileDirectory) = with(directory) {
        sampleFormat[0] == SAMPLE_FORMAT_SIGNED_INT && bitsPerSample[0] == INT16_BITS_PER_SAMPLE && samplesPerPixel == SAMPLES_PER_PIXEL
    }

    protected open fun isFloat32Tiff(directory: FileDirectory) = with(directory) {
        sampleFormat[0] == SAMPLE_FORMAT_FLOAT && bitsPerSample[0] == FLOAT_BITS_PER_SAMPLE && samplesPerPixel == SAMPLES_PER_PIXEL
    }

    fun decodePng(
        bytes: ByteArray, tileScale: Float = 1.0f, tileOffset: Float = 0.0f,
        coverageScale: Float = 1.0f, coverageOffset: Float = 0.0f, dataNull: Float? = null
    ): Buffer {
        val png = PngReaderInt(ByteArrayInputStream(bytes))
        require(png.imgInfo.channels == 1 && png.imgInfo.bitDepth == 16) {
            logMessage(
                ERROR, "ElevationDecoder", "decodePng",
                "The elevation data tile is expected to be a single channel 16 bit unsigned short"
            )
        }

        // Check if scale and offset should be applied to elevation data
        return if (tileScale != 1.0f || tileOffset != 0.0f || coverageScale != 1.0f || coverageOffset != 0.0f) {
            // Apply scale and offset to INT16 values (except null data value) and return them as FLOAT32
            val pixels = FloatArray(png.imgInfo.cols * png.imgInfo.rows)
            var row = 0
            while (png.hasMoreRows()) {
                png.readRowInt().scanline.forEachIndexed { i, pixel ->
                    val rawHeight = pixel.toFloat()
                    pixels[row * png.imgInfo.cols + i] = if (rawHeight == dataNull) Float.MAX_VALUE
                    else (rawHeight * tileScale + tileOffset) * coverageScale + coverageOffset
                }
                row++
            }
            png.close()
            FloatBuffer.wrap(pixels)
        } else {
            // Use INT16 value as is
            val pixels = ShortArray(png.imgInfo.cols * png.imgInfo.rows)
            var row = 0
            val dataNullInt = dataNull?.roundToInt()
            while (png.hasMoreRows()) {
                png.readRowInt().scanline.forEachIndexed { i, pixel ->
                    pixels[row * png.imgInfo.cols + i] = if (pixel == dataNullInt) Short.MIN_VALUE else pixel.toShort()
                }
                row++
            }
            png.close()
            ShortBuffer.wrap(pixels)
        }
    }

    protected open fun wrapBytes(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun encodePng(pixels: ShortBuffer, tileWidth: Int, tileHeight: Int): ByteArray = ByteArrayOutputStream().use {
        val imageInfo = ImageInfo(tileWidth, tileHeight, 16, false, true, false)
        val writer = PngWriter(it, imageInfo)
        for (y in 0 until tileHeight) {
            val row = ImageLineInt(writer.imgInfo, IntArray(tileWidth))
            for (x in 0 until tileWidth) row.scanline[x] = pixels[y * tileWidth + x].toInt()
            writer.writeRow(row)
        }
        writer.end()
        pixels.clear()
        it.toByteArray()
    }

    @Throws(IOException::class)
    fun encodeTiff(pixels: FloatBuffer, tileWidth: Int, tileHeight: Int): ByteArray {
        val directory = createDirectory(tileWidth, tileHeight)
        for (y in 0 until tileHeight) for (x in 0 until tileWidth) {
            directory.writeRasters.setFirstPixelSample(x, y, pixels[y * tileWidth + x])
        }
        pixels.clear()
        val tiffImage = TIFFImage()
        tiffImage.add(directory)
        return TiffWriter.writeTiffToBytes(tiffImage)
    }

    protected open fun createDirectory(tileWidth: Int, tileHeight: Int) = FileDirectory().apply {
        val rasters = Rasters(tileWidth, tileHeight, SAMPLES_PER_PIXEL, FLOAT_BITS_PER_SAMPLE, SAMPLE_FORMAT_FLOAT)
        setImageWidth(tileWidth)
        setImageHeight(tileHeight)
        setSampleFormat(SAMPLE_FORMAT_FLOAT)
        setBitsPerSample(FLOAT_BITS_PER_SAMPLE)
        setRowsPerStrip(rasters.calculateRowsPerStrip(PLANAR_CONFIGURATION_CHUNKY))
        samplesPerPixel = SAMPLES_PER_PIXEL
        compression = COMPRESSION_NO
        photometricInterpretation = PHOTOMETRIC_INTERPRETATION_BLACK_IS_ZERO
        planarConfiguration = PLANAR_CONFIGURATION_CHUNKY
        writeRasters = rasters
    }

    companion object {
        private const val SAMPLES_PER_PIXEL = 1
        private const val INT16_BITS_PER_SAMPLE = 16
        private const val FLOAT_BITS_PER_SAMPLE = 32
    }
}