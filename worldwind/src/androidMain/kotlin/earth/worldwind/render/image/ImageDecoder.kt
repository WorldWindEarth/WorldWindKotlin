package earth.worldwind.render.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.appcompat.content.res.AppCompatResources
import earth.worldwind.render.image.ImageConfig.RGBA_8888
import earth.worldwind.render.image.ImageConfig.RGB_565
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.net.URL

open class ImageDecoder(val context: Context): Closeable {
    protected val httpClient by lazy { DefaultHttpClient() }

    override fun close() = httpClient.close()

    open suspend fun decodeImage(imageSource: ImageSource, imageOptions: ImageOptions?) = withContext(Dispatchers.IO) {
        when {
            imageSource.isBitmap -> imageSource.asBitmap()
            imageSource.isBitmapFactory -> imageSource.asBitmapFactory().createBitmap()
            imageSource.isResource -> decodeResource(imageSource.asResource(), imageOptions)
            imageSource.isFile -> decodeFile(imageSource.asFile(), imageOptions)
            imageSource.isUrl -> decodeUrl(imageSource.asUrl(), imageOptions)
            else -> decodeUnrecognized(imageSource)
        }?.let {
            // Apply bitmap transformation if required
            imageSource.postprocessor?.process(it) ?: it
        }
    }

    protected open fun decodeResource(id: Int, imageOptions: ImageOptions?) =
        BitmapFactory.decodeResource(context.resources, id, bitmapFactoryOptions(imageOptions))
            // Use AppCompatResources to read vector SVG images from resources
            ?: AppCompatResources.getDrawable(context, id)?.run {
                val bitmap = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                setBounds(0, 0, canvas.width, canvas.height)
                draw(canvas)
                bitmap
            }

    protected open fun decodeFile(file: File, imageOptions: ImageOptions?): Bitmap? =
        BitmapFactory.decodeFile(file.absolutePath, bitmapFactoryOptions(imageOptions))

    protected open suspend fun decodeUrl(url: URL, imageOptions: ImageOptions?): Bitmap? {
        val response = httpClient.get(url) {
            headers {
                // Some map servers block requests without Accept and User-Agent headers
                append(HttpHeaders.Accept, "image/*,*/*")
                append(HttpHeaders.UserAgent, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36")
            }
        }
        return if (response.status == HttpStatusCode.OK) {
            val bytes = response.readRawBytes()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bitmapFactoryOptions(imageOptions))
        } else null // Result is not an image, access denied or server error
    }

    protected open fun decodeUnrecognized(imageSource: ImageSource): Bitmap? {
        log(WARN, "Unrecognized image source '$imageSource'")
        return null
    }

    protected open fun bitmapFactoryOptions(imageOptions: ImageOptions?): BitmapFactory.Options {
        val factoryOptions = BitmapFactory.Options()
        factoryOptions.inScaled = false // suppress default image scaling; load the image in its native dimensions
        when (imageOptions?.imageConfig) {
            RGBA_8888 -> factoryOptions.inPreferredConfig = Bitmap.Config.ARGB_8888
            RGB_565 -> factoryOptions.inPreferredConfig = Bitmap.Config.RGB_565
            null -> {}
        }
        return factoryOptions
    }
}