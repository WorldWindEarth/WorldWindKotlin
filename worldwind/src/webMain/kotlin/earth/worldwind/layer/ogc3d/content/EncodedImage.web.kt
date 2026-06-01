package earth.worldwind.layer.ogc3d.content

import earth.worldwind.render.Texture
import earth.worldwind.render.image.ImageTexture
import earth.worldwind.layer.cache.toUint8Array
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import org.w3c.dom.ImageBitmap
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.Promise
import kotlin.js.js

/** Native browser `createImageBitmap(Blob)`. */
internal actual suspend fun decodeTileTexture(bytes: ByteArray): Texture? {
    if (bytes.isEmpty()) return null
    val u8 = bytes.toUint8Array()
    val blobParts = JsArray<JsAny?>()
    blobParts[0] = u8
    // Empty MIME — createImageBitmap sniffs the magic itself.
    val webBlob = Blob(blobParts, BlobPropertyBag(""))
    return try {
        val bitmap = createImageBitmap(webBlob).await()
        ImageTexture(bitmap, bitmap.width, bitmap.height)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        log(Logger.WARN, "decodeTileTexture: createImageBitmap failed: ${e.message}")
        null
    }
}

// Kotlin/Wasm requires js() bodies at file scope.
@Suppress("UNUSED_PARAMETER")
private fun createImageBitmap(blob: Blob): Promise<ImageBitmap> = js("self.createImageBitmap(blob)")
