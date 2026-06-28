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
import io.ktor.utils.io.discard
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
            imageSource.isImageFactory -> imageSource.asImageFactory().createBitmap()
            imageSource.isResource -> decodeResource(imageSource.asResource(), imageOptions)
            imageSource.isFile -> decodeFile(imageSource.asFile(), imageOptions)
            imageSource.isUrl -> decodeUrl(imageSource.asUrl(), imageOptions)
            imageSource.isZipEntry -> decodeZipEntry(imageSource.asZipEntry(), imageOptions)
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

    protected open fun decodeFile(file: File, imageOptions: ImageOptions?): Bitmap? {
        val options = bitmapFactoryOptions(imageOptions)
        val cap = imageOptions?.maxDimension ?: 0
        if (cap > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, cap)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

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
            val options = bitmapFactoryOptions(imageOptions)
            val cap = imageOptions?.maxDimension ?: 0
            if (cap > 0) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, cap)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } else {
            // Drain the non-OK body so ktor releases the call
            response.bodyAsChannel().discard()
            null
        }
    }

    /** Decodes a texture straight out of an open [ZipFile][java.util.zip.ZipFile] entry (no extraction). */
    protected open fun decodeZipEntry(ref: ZipEntryImageRef, imageOptions: ImageOptions?): Bitmap? {
        val entry = ref.zip.getEntry(ref.entry) ?: return null
        val options = bitmapFactoryOptions(imageOptions)
        val cap = imageOptions?.maxDimension ?: 0
        if (cap > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ref.zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
            options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, cap)
        }
        return ref.zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** Largest power-of-two subsample factor keeping `max(width, height) <= maxDimension`. */
    protected fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        if (maxDimension <= 0 || width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / sample > maxDimension) sample *= 2
        return sample
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