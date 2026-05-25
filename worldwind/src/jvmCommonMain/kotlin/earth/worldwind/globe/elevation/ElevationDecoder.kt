package earth.worldwind.globe.elevation

import ar.com.hjg.pngj.ImageInfo
import ar.com.hjg.pngj.ImageLineInt
import ar.com.hjg.pngj.PngReaderInt
import ar.com.hjg.pngj.PngWriter
import earth.worldwind.formats.dted.DTED
import earth.worldwind.formats.dted.read
import earth.worldwind.formats.geotiff.GeoTiffReader
import earth.worldwind.formats.geotiff.GeoTiffWriter
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
import java.io.*
import java.net.URL
import java.nio.*
import kotlin.math.roundToInt

open class ElevationDecoder: Closeable {
    protected val httpClient by lazy { DefaultHttpClient() }

    override fun close() = httpClient.close()

    open suspend fun decodeElevation(elevationSource: ElevationSource) = withContext(Dispatchers.IO) {
        val raw = elevationSource.asUnrecognized()
        decodeBuffer(when {
            raw is TileSourceElevationRef -> decodeTileSource(raw, elevationSource.postprocessor)
            elevationSource.isElevationDataFactory -> elevationSource.asElevationDataFactory().fetchElevationData()
            elevationSource.isFile -> decodeFile(elevationSource.asFile(), elevationSource.postprocessor)
            elevationSource.isUrl -> decodeUrl(elevationSource.asUrl(), elevationSource.postprocessor)
            else -> decodeUnrecognized(elevationSource)
        })
    }

    /**
     * Fetch bytes through the [TileSourceElevationRef]'s [TileSource] then dispatch through
     * the standard byte-stream decoder.
     *
     * **Specific Content-Type wins; requested format is the fallback.** A server response
     * with a specific elevation MIME (`application/bil16`, `image/tiff`, …) is trusted —
     * dispatching on it catches service-error `text/xml` responses at HTTP 200 that would
     * otherwise be parsed as BIL16 garbage. Cache hits (no header) AND *generic* responses
     * like `application/octet-stream` (used by WMS / WCS servers that don't advertise a
     * specific elevation MIME but still return correct bytes) fall back to
     * [TileSourceElevationRef.outputFormat]. Without this generic-MIME fallback the
     * decoder threw "Format not supported: application/octet-stream" on every fresh fetch
     * from such servers while the same bytes decoded fine on the subsequent cache hit —
     * "fresh fetch fails / cache replay works" observed as "terrain at 0 altitude after
     * v3 migration" when downloads silently fail layer-wide.
     */
    protected open suspend fun decodeTileSource(
        ref: TileSourceElevationRef, postprocessor: ResourcePostprocessor?,
    ): Buffer? {
        val blob = ref.source.fetchTile(ref.z, ref.x, ref.y) ?: return null
        if (blob.isEmpty) return null
        val serverType = blob.contentType
        val effectiveType =
            if (serverType == null || serverType.equals("application/octet-stream", ignoreCase = true))
                ref.outputFormat
            else serverType
        return decodeBytes(blob.bytes, effectiveType, postprocessor)
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

    /** Public [decodeBuffer] for cache-only readers that fetch their own [Buffer] (e.g.
     *  `GpkgCachedElevationSourceFactory.readCachedTileArray`) and need the same
     *  `Buffer -> ShortArray` conversion the standard decode path applies. */
    fun bufferToShortArray(buffer: Buffer?): ShortArray? = decodeBuffer(buffer)

    protected open suspend fun decodeFile(file: File, postprocessor: ResourcePostprocessor?): Buffer? {
        val ext = file.name.substring(file.name.lastIndexOf('.') + 1).lowercase()
        // DTED files can be ~26 MB on .dt2 — stream the column records via RandomAccessFile
        // instead of slurping the whole file. Other formats stay on the byte-array path.
        if (ext == "dt0" || ext == "dt1" || ext == "dt2") {
            val buffer = ShortBuffer.wrap(DTED.read(file).elevations)
            return postprocessor?.process(buffer) ?: buffer
        }
        val contentType = when (ext) {
            "png" -> "image/png"
            "tiff" -> "image/tiff"
            "bil16" -> "application/bil16"
            "bil32" -> "application/bil32"
            else -> error(logMessage(ERROR, "ElevationDecoder", "decodeFile", "Unknown mime-type"))
        }
        return decodeBytes(FileInputStream(file).readBytes(), contentType, postprocessor)
    }

    protected open suspend fun decodeUrl(url: URL, postprocessor: ResourcePostprocessor?) = httpClient.get(url).let {
        if (it.status == HttpStatusCode.OK) {
            decodeBytes(it.readRawBytes(), it.contentType().toString(), postprocessor)
        } else null // The result is not an elevation data, access denied or server error
    }

    protected open fun decodeUnrecognized(imageSource: ElevationSource): Buffer? {
        log(WARN, "Unrecognized image source '$imageSource'")
        return null
    }

    /**
     * Public delegate for decoding raw network bytes by elevation MIME. Used by cache
     * factories that fetch their own bytes (bypassing `decodeTileSource`) but still want
     * the standard format-dispatch table. No postprocessor — the caller is responsible
     * for any side-effects (cache write-through, etc.) it wants on the decoded buffer.
     */
    open suspend fun decodeElevationBytes(bytes: ByteArray, contentType: String?): Buffer? =
        decodeBytes(bytes, contentType, postprocessor = null)

    protected open suspend fun decodeBytes(bytes: ByteArray, contentType: String?, postprocessor: ResourcePostprocessor?) = when {
        contentType.equals("image/bil", true) ||
        contentType.equals("application/bil", true) ||
        contentType.equals("application/bil16", true) -> wrapBytes(bytes).asShortBuffer()
        contentType.equals("application/bil32", true) -> wrapBytes(bytes).asFloatBuffer()
        // Accept the OGC MIME plus the bare WCS/WMS FORMAT spellings ("geotiff",
        // "image/geotiff", "GeoTIFF") so a cache-hit replay through this decoder doesn't
        // need to know the precise response Content-Type the server returned originally.
        contentType.equals("image/tiff", true) ||
        contentType.equals("image/geotiff", true) ||
        contentType.equals("application/geotiff", true) ||
        contentType.equals("geotiff", true) ||
        contentType.equals("tiff", true) -> decodeTiff(bytes)
        contentType.equals("image/png", true) -> decodePng(bytes)
        contentType.equals("application/dted", true) ||
        contentType.equals("application/dted0", true) ||
        contentType.equals("application/dted1", true) ||
        contentType.equals("application/dted2", true) -> ShortBuffer.wrap(DTED(bytes).elevations)
        else -> throw RuntimeException(
            logMessage(ERROR, "ElevationDecoder", "decodeBytes", "Format not supported: $contentType")
        )
    }.let { postprocessor?.process(it) ?: it } // Process loaded elevation data if necessary

    /** Decode a TIFF/GeoTIFF blob into a [ShortBuffer]. Uses the cross-platform
     *  [GeoTiffReader] (common code shared with JS / iOS); the prior `mil.nga.tiff`
     *  decode path was JVM-only and forced every platform to maintain its own TIFF
     *  parser. Encoding still goes through `mil.nga.tiff` via [encodeTiff] - moving
     *  that to common is a follow-up. */
    open fun decodeTiff(bytes: ByteArray): Buffer = ShortBuffer.wrap(GeoTiffReader(bytes).createElevationShortArray())

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
        writer.setCompLevel(1) // Use fastest compression for local tile cache
        val row = ImageLineInt(writer.imgInfo, IntArray(tileWidth))
        for (y in 0 until tileHeight) {
            for (x in 0 until tileWidth) row.scanline[x] = pixels[y * tileWidth + x].toInt()
            writer.writeRow(row)
        }
        writer.end()
        pixels.clear()
        it.toByteArray()
    }

    /** Serialise a `tileWidth × tileHeight` FLOAT32 elevation grid as a TIFF byte array.
     *  Uses the cross-platform [GeoTiffWriter] (no `mil.nga.tiff` dependency); produces a
     *  baseline single-strip uncompressed FLOAT32 grayscale TIFF that round-trips through
     *  [GeoTiffReader] / any standard TIFF library. Used by `GpkgElevationDataFactory` to
     *  store elevation tiles in the GeoPackage cache. */
    fun encodeTiff(pixels: FloatBuffer, tileWidth: Int, tileHeight: Int): ByteArray {
        val flat = FloatArray(tileWidth * tileHeight)
        pixels.get(flat)
        return GeoTiffWriter.writeFloatGrayscaleTiff(flat, tileWidth, tileHeight)
    }
}