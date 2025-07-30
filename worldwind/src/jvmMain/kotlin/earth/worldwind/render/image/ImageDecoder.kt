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

open class ImageDecoder: Closeable {
    protected val httpClient by lazy { DefaultHttpClient() }

    override fun close() = httpClient.close()

    open suspend fun decodeImage(imageSource: ImageSource, imageOptions: ImageOptions?) = withContext(Dispatchers.IO) {
        val image = when {
            imageSource.isResource -> imageSource.asResource().image
            imageSource.isImage -> imageSource.asImage()
            imageSource.isImageFactory -> imageSource.asImageFactory().createImage()
            imageSource.isFile -> decodeFile(imageSource.asFile())
            imageSource.isUrl -> decodeUrl(imageSource.asUrl())
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

    protected open fun decodeFile(file: File): BufferedImage = ImageIO.read(file)

    @Suppress("BlockingMethodInNonBlockingContext")
    protected open suspend fun decodeUrl(url: URL) =
        if (url.protocol.equals("http", true) || url.protocol.equals("https", true)) {
            val response = httpClient.get(url) {
                headers {
                    // Some map servers block requests without Accept and User-Agent headers
                    append(HttpHeaders.Accept, "image/*,*/*")
                    append(HttpHeaders.UserAgent, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36")
                }
            }
            ImageIO.read(ByteArrayInputStream(response.readRawBytes()))
        } else ImageIO.read(url)

    protected open fun decodeUnrecognized(imageSource: ImageSource): BufferedImage? {
        log(WARN, "Unrecognized image source '$imageSource'")
        return null
    }
}