package earth.worldwind.render.image

import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.net.URL
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream

open class ImageDecoder: Closeable {
    protected val httpClient by lazy { DefaultHttpClient() }

    override fun close() = httpClient.close()

    open suspend fun decodeImage(imageSource: ImageSource, imageOptions: ImageOptions?) = withContext(Dispatchers.IO) {
        val image = when {
            imageSource.isResource -> imageSource.asResource().image
            imageSource.isImage -> imageSource.asImage()
            imageSource.isImageFactory -> imageSource.asImageFactory().createImage()
            imageSource.isFile -> decodeFile(imageSource.asFile(), imageOptions)
            imageSource.isUrl -> decodeUrl(imageSource.asUrl(), imageOptions)
            imageSource.isZipEntry -> decodeZipEntry(imageSource.asZipEntry(), imageOptions)
            else -> decodeUnrecognized(imageSource)
        }?.let {
            // Apply image transformation if required
            imageSource.postprocessor?.process(it) ?: it
        }
        // Convert image to a required type
        val type = when (imageOptions?.imageConfig) {
            ImageConfig.RGB_565 -> BufferedImage.TYPE_INT_RGB
            ImageConfig.RGBA_8888 -> BufferedImage.TYPE_INT_ARGB
            else -> null
        }
        if (image != null && type != null && image.type != type) {
            val convertedImage = BufferedImage(image.width, image.height, type)
            convertedImage.graphics.drawImage(image, 0, 0, null)
            convertedImage
        } else image
    }

    protected open fun decodeFile(file: File, imageOptions: ImageOptions?): BufferedImage =
        readSubsampled(ImageIO.createImageInputStream(file), imageOptions?.maxDimension ?: 0) ?: ImageIO.read(file)

    /** Decodes a texture straight out of an open [ZipFile][java.util.zip.ZipFile] entry (no extraction). */
    protected open fun decodeZipEntry(ref: ZipEntryImageRef, imageOptions: ImageOptions?): BufferedImage? {
        val entry = ref.zip.getEntry(ref.entry) ?: return null
        val sub = ref.zip.getInputStream(entry).use {
            readSubsampled(ImageIO.createImageInputStream(it), imageOptions?.maxDimension ?: 0)
        }
        return sub ?: ref.zip.getInputStream(entry).use { ImageIO.read(it) }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    protected open suspend fun decodeUrl(url: URL, imageOptions: ImageOptions?): BufferedImage? {
        val cap = imageOptions?.maxDimension ?: 0
        if (url.protocol.equals("http", true) || url.protocol.equals("https", true)) {
            val response = httpClient.get(url) {
                headers {
                    // Some map servers block requests without Accept and User-Agent headers
                    append(HttpHeaders.Accept, "image/*,*/*")
                    append(HttpHeaders.UserAgent, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36")
                }
            }
            val bytes = response.readRawBytes()
            ByteArrayInputStream(bytes).use { readSubsampled(ImageIO.createImageInputStream(it), cap) }?.let { return it }
            return ByteArrayInputStream(bytes).use { ImageIO.read(it) } // fallback on a fresh stream
        }
        // Local URL (e.g. file://): close the opened stream to avoid leaking a file descriptor.
        url.openStream().use { readSubsampled(ImageIO.createImageInputStream(it), cap) }?.let { return it }
        return ImageIO.read(url)
    }

    /**
     * Decodes [input] downsampled so `max(width, height) <= maxDimension` (power-of-two subsampling
     * at the ImageReader level, so the full-resolution bitmap is never allocated). Returns `null`
     * when no reader is available or the stream is null, so callers can fall back to [ImageIO.read].
     */
    protected fun readSubsampled(input: ImageInputStream?, maxDimension: Int): BufferedImage? {
        input ?: return null
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) { input.close(); return null }
        val reader = readers.next()
        return try {
            reader.setInput(input, true, true)
            val param = reader.defaultReadParam
            if (maxDimension > 0) {
                var sample = 1
                while (maxOf(reader.getWidth(0), reader.getHeight(0)) / sample > maxDimension) sample *= 2
                if (sample > 1) param.setSourceSubsampling(sample, sample, 0, 0)
            }
            reader.read(0, param)
        } finally {
            reader.dispose()
            input.close()
        }
    }

    protected open fun decodeUnrecognized(imageSource: ImageSource): BufferedImage? {
        log(WARN, "Unrecognized image source '$imageSource'")
        return null
    }
}