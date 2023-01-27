package earth.worldwind.globe.elevation

import earth.worldwind.formats.tiff.Subfile
import earth.worldwind.formats.tiff.Tiff
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.ResourcePostprocessor
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.nio.*
import kotlin.math.roundToInt

open class ElevationDecoder: Closeable {
    protected val httpClient = DefaultHttpClient()

    override fun close() = httpClient.close()

    open suspend fun decodeElevation(elevationSource: ElevationSource) = when {
        elevationSource.isElevationFactory -> decodeBuffer(elevationSource.asElevationFactory().fetchTileData())
        elevationSource.isFile -> decodeFile(elevationSource.asFile(), elevationSource.bufferPostprocessor)
        elevationSource.isUrl -> decodeUrl(elevationSource.asUrl(), elevationSource.bufferPostprocessor)
        else -> decodeUnrecognized(elevationSource)
    }

    protected open fun decodeBuffer(buffer: Buffer?) = when (buffer) {
        is ShortBuffer -> ShortArray(buffer.remaining()).also { buffer.get(it) }
        is FloatBuffer -> ShortArray(buffer.remaining()) { buffer.get().roundToInt().toShort() }
        else -> null
    }

    protected open suspend fun decodeFile(file: File, postprocessor: ResourcePostprocessor<Buffer>?) =
        when(file.name.substring(file.name.lastIndexOf('.') + 1)) {
            "tiff" -> "image/tiff"
            "bil16" -> "application/bil16"
            "bil32" -> "application/bil32"
            else -> null
        }?.let { decodeBytes(FileInputStream(file).readBytes(), it, postprocessor) } ?: error(
            logMessage(ERROR, "ElevationDecoder", "decodeFile", "Unknown mime-type")
        )

    protected open suspend fun decodeUrl(url: URL, postprocessor: ResourcePostprocessor<Buffer>?) = httpClient.get(url).let {
        if (it.status == HttpStatusCode.OK) {
            decodeBytes(it.readBytes(), it.contentType().toString(), postprocessor)
        } else null // Result is not an elevation data, access denied or server error
    }

    protected open fun decodeUnrecognized(imageSource: ElevationSource): ShortArray? {
        log(WARN, "Unrecognized image source '$imageSource'")
        return null
    }

    protected open suspend fun decodeBytes(bytes: ByteArray, contentType: String?, postprocessor: ResourcePostprocessor<Buffer>?) = decodeBuffer(
        when {
            contentType.equals("image/bil", true) ||
            contentType.equals("application/bil", true) ||
            contentType.equals("application/bil16", true) -> wrapBytes(bytes).asShortBuffer()
            contentType.equals("application/bil32", true) -> wrapBytes(bytes).asFloatBuffer()
            contentType.equals("image/tiff", true) -> decodeTiffData(bytes)
            else -> throw RuntimeException(
                logMessage(ERROR, "ElevationDecoder", "decodeBytes", "Format not supported: $contentType")
            )
        }.let { postprocessor?.process(it) ?: it } // Process loaded elevation data if necessary
    )

    protected open fun decodeTiffData(bytes: ByteArray): Buffer {
        val tiff = Tiff(ByteBuffer.wrap(bytes))
        val subfile = tiff.subfiles[0]
        val result = subfile.getData(ByteBuffer.allocate(subfile.getDataSize())).apply { clear() }
        // check that the format of the subfile matches our supported data types
        return when {
            isInt16Tiff(subfile) -> result.asShortBuffer()
            isFloat32Tiff(subfile) -> result.asFloatBuffer()
            else -> error(
                logMessage(
                    ERROR, "ElevationDecoder", "decodeTiffData", "Tiff file format not supported"
                )
            )
        }
    }

    protected open fun isInt16Tiff(subfile: Subfile) = subfile.run {
        sampleFormat[0] == Tiff.TWOS_COMP_SIGNED_INT && bitsPerSample[0] == 16 && samplesPerPixel == 1 && compression == 1
    }

    protected open fun isFloat32Tiff(subfile: Subfile) = subfile.run {
        sampleFormat[0] == Tiff.FLOATING_POINT && bitsPerSample[0] == 32 && samplesPerPixel == 1 && compression == 1
    }

    protected open fun wrapBytes(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}