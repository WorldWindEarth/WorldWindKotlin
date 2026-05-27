package earth.worldwind.formats.nitf

import earth.worldwind.render.image.ImageSource
import kotlinx.browser.document
import org.khronos.webgl.Uint8ClampedArray
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.ImageData

actual fun NitfImage.toImageSource(): ImageSource {
    // `this` inside a js("…") literal is the emitted JS scope's `this`, not the Kotlin
    // receiver — captured as locals so `js()` and `new ImageData(...)` see them.
    val w = width
    val h = height
    val rgba = js("new Uint8ClampedArray(w * h * 4)").unsafeCast<Uint8ClampedArray>()
    // asDynamic emits a JS bracket assignment. The stdlib's typed `set(index, Byte)`
    // operator loses values 128..255 to the Uint8ClampedArray clamp via signed-byte
    // round-trip, and an external `operator fun set(index, value)` would dispatch to
    // the array-copy overload instead.
    val data = rgba.asDynamic()
    val src = argb
    var di = 0
    for (i in 0 until w * h) {
        val v = src[i]
        data[di] = (v ushr 16) and 0xFF     // R
        data[di + 1] = (v ushr 8) and 0xFF  // G
        data[di + 2] = v and 0xFF           // B
        data[di + 3] = (v ushr 24) and 0xFF // A
        di += 4
    }

    val imageData = js("new ImageData(rgba, w, h)").unsafeCast<ImageData>()
    val canvas = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
    canvas.width = w
    canvas.height = h
    val ctx = canvas.getContext("2d").unsafeCast<CanvasRenderingContext2D>()
    ctx.putImageData(imageData, 0.0, 0.0)
    return ImageSource.fromImage(canvas)
}
