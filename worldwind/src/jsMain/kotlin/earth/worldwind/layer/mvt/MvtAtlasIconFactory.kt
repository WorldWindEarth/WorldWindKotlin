package earth.worldwind.layer.mvt

import earth.worldwind.render.image.ImageSource
import kotlinx.browser.document
import kotlinx.coroutines.await
import org.khronos.webgl.TexImageSource
import org.khronos.webgl.Uint8Array
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import kotlin.js.Promise

actual class MvtAtlasIconFactory actual constructor(
    actual val atlas: MvtSpriteAtlas,
    actual val entry: MvtSpriteEntry,
) : ImageSource.ImageFactory {

    override suspend fun createImage(): TexImageSource? {
        val whole = decodedAtlasFor(atlas).await() ?: return null
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = entry.width
        canvas.height = entry.height
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.drawImage(
            whole,
            entry.x.toDouble(), entry.y.toDouble(),
            entry.width.toDouble(), entry.height.toDouble(),
            0.0, 0.0,
            entry.width.toDouble(), entry.height.toDouble(),
        )
        return canvas
    }

    actual companion object {
        private val decodeCache = HashMap<MvtSpriteAtlas, Promise<HTMLImageElement?>>()

        actual fun releaseDecodedImage(atlas: MvtSpriteAtlas) { decodeCache.remove(atlas) }

        private fun decodedAtlasFor(atlas: MvtSpriteAtlas): Promise<HTMLImageElement?> {
            decodeCache[atlas]?.let { return it }
            val promise = Promise<HTMLImageElement?> { resolve, _ ->
                // Wrap the byte array as a Blob → object URL → HTMLImageElement.onload. The
                // object URL is revoked after load to release the blob; the HTMLImageElement
                // itself retains the decoded pixels independently.
                val arr = Uint8Array(atlas.imageBytes.toTypedArray())
                val blob = Blob(arrayOf(arr), BlobPropertyBag(type = "image/png"))
                val url = URL.createObjectURL(blob)
                val img = document.createElement("img") as HTMLImageElement
                img.onload = {
                    URL.revokeObjectURL(url)
                    resolve(img)
                }
                img.onerror = { _, _, _, _, _ ->
                    URL.revokeObjectURL(url)
                    resolve(null)
                }
                img.src = url
            }
            decodeCache[atlas] = promise
            return promise
        }

    }
}
