package earth.worldwind.formats.nitf

import earth.worldwind.render.image.ImageSource
import kotlinx.browser.document
import org.khronos.webgl.Uint8ClampedArray
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.ImageData
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.js.unsafeCast

actual fun NitfImage.toImageSource(): ImageSource {
    val w = width
    val h = height
    val rgba = newUint8ClampedArray(w * h * 4)
    val src = argb
    var di = 0
    for (i in 0 until w * h) {
        val v = src[i]
        // Direct bracket assignment via top-level js() helper. The stdlib's typed `set(index, Byte)`
        // operator loses values 128..255 to the Uint8ClampedArray clamp via signed-byte round-trip.
        setClampedAt(rgba, di, (v ushr 16) and 0xFF)     // R
        setClampedAt(rgba, di + 1, (v ushr 8) and 0xFF)  // G
        setClampedAt(rgba, di + 2, v and 0xFF)           // B
        setClampedAt(rgba, di + 3, (v ushr 24) and 0xFF) // A
        di += 4
    }

    val imageData = newImageData(rgba, w, h)
    val canvas = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
    canvas.width = w
    canvas.height = h
    val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
    ctx.putImageData(imageData, 0.0, 0.0)
    return ImageSource.fromImage(canvas)
}

// Top-level js() factories/setters (Kotlin/Wasm requires js() bodies at file scope; params bind by name).
@Suppress("UNUSED_PARAMETER") private fun newUint8ClampedArray(size: Int): Uint8ClampedArray = js("new Uint8ClampedArray(size)")
@Suppress("UNUSED_PARAMETER") private fun setClampedAt(array: Uint8ClampedArray, index: Int, value: Int): JsAny? = js("array[index] = value")
@Suppress("UNUSED_PARAMETER") private fun newImageData(data: Uint8ClampedArray, w: Int, h: Int): ImageData = js("new ImageData(data, w, h)")
