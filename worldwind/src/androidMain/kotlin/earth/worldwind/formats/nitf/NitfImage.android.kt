package earth.worldwind.formats.nitf

import android.graphics.Bitmap
import earth.worldwind.render.image.ImageSource

actual fun NitfImage.toImageSource(): ImageSource {
    // Bitmap.createBitmap consumes the same 0xAARRGGBB layout the NITF
    // decoder emits, so no per-pixel re-shuffling is needed.
    val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    return ImageSource.fromBitmap(bitmap)
}
