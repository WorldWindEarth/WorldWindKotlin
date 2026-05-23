package earth.worldwind.formats.nitf

import earth.worldwind.render.image.ImageSource
import kotlinx.browser.document
import org.khronos.webgl.Uint8ClampedArray
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.ImageData

actual fun NitfImage.toImageSource(): ImageSource {
    // Build an ImageData buffer in RGBA-byte order (browser canvas convention),
    // unswizzling our packed 0xAARRGGBB ints. Then paint it into an offscreen
    // canvas and hand the canvas to the surface-image pipeline — same path the
    // existing MilStd2525 renderer uses.
    val rgba = js("new Uint8ClampedArray(this.width * this.height * 4)")
        .unsafeCast<Uint8ClampedArray>()
    val data = rgba.asDynamic()
    val src = argb
    var di = 0
    for (i in 0 until width * height) {
        val v = src[i]
        data[di] = (v ushr 16) and 0xFF     // R
        data[di + 1] = (v ushr 8) and 0xFF  // G
        data[di + 2] = v and 0xFF           // B
        data[di + 3] = (v ushr 24) and 0xFF // A
        di += 4
    }

    val imageData = js("new ImageData(rgba, this.width, this.height)").unsafeCast<ImageData>()
    val canvas = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
    canvas.width = width
    canvas.height = height
    val ctx = canvas.getContext("2d").unsafeCast<CanvasRenderingContext2D>()
    ctx.putImageData(imageData, 0.0, 0.0)
    return ImageSource.fromImage(canvas)
}
