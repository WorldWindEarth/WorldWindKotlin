package earth.worldwind.render.image

import kotlinx.browser.document
import org.khronos.webgl.Uint8ClampedArray
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.ImageData
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.js.unsafeCast

actual fun argbImageSource(key: Any, factory: suspend () -> ArgbImage?): ImageSource =
    ImageSource.fromImageFactory(ArgbImageFactory(key, factory))

/** Web [ImageSource.ImageFactory] materializing [ArgbImage] pixels onto a 2D canvas, which
 *  WebGL accepts directly as a texture source. */
private class ArgbImageFactory(
    private val key: Any, private val factory: suspend () -> ArgbImage?
) : ImageSource.ImageFactory {
    override suspend fun createImage(): org.khronos.webgl.TexImageSource? {
        val image = factory() ?: return null
        val w = image.width
        val h = image.height
        val rgba = newUint8ClampedArray(w * h * 4)
        val src = image.argb
        var di = 0
        for (i in 0 until w * h) {
            val v = src[i]
            // Direct bracket assignment via top-level js() helper. The stdlib's typed
            // `set(index, Byte)` operator loses values 128..255 to the Uint8ClampedArray
            // clamp via signed-byte round-trip.
            setClampedAt(rgba, di, (v ushr 16) and 0xFF)     // R
            setClampedAt(rgba, di + 1, (v ushr 8) and 0xFF)  // G
            setClampedAt(rgba, di + 2, v and 0xFF)           // B
            setClampedAt(rgba, di + 3, (v ushr 24) and 0xFF) // A
            di += 4
        }
        val canvas = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
        canvas.width = w
        canvas.height = h
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.putImageData(newImageData(rgba, w, h), 0.0, 0.0)
        return canvas
    }

    override fun equals(other: Any?) = other is ArgbImageFactory && key == other.key

    override fun hashCode() = key.hashCode()

    override fun toString() = "ArgbImageFactory($key)"
}

// Top-level js() factories/setters (Kotlin/Wasm requires js() bodies at file scope; params bind by name).
@Suppress("UNUSED_PARAMETER") private fun newUint8ClampedArray(size: Int): Uint8ClampedArray = js("new Uint8ClampedArray(size)")
@Suppress("UNUSED_PARAMETER") private fun setClampedAt(array: Uint8ClampedArray, index: Int, value: Int): JsAny? = js("array[index] = value")
@Suppress("UNUSED_PARAMETER") private fun newImageData(data: Uint8ClampedArray, w: Int, h: Int): ImageData = js("new ImageData(data, w, h)")
