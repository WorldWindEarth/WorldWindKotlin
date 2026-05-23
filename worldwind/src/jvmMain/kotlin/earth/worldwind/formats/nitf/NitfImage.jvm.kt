package earth.worldwind.formats.nitf

import earth.worldwind.render.image.ImageSource
import java.awt.image.BufferedImage

actual fun NitfImage.toImageSource(): ImageSource {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    // setRGB takes the same 0xAARRGGBB layout the NITF decoder emits.
    image.setRGB(0, 0, width, height, argb, 0, width)
    return ImageSource.fromImage(image)
}
