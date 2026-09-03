package earth.worldwind.render.image

import java.awt.image.BufferedImage

actual fun argbImageSource(key: Any, factory: suspend () -> ArgbImage?): ImageSource =
    ImageSource.fromImageFactory(ArgbImageFactory(key, factory))

/** JVM [ImageSource.ImageFactory] materializing [ArgbImage] pixels as a `TYPE_INT_ARGB`
 *  [BufferedImage] — `setRGB` consumes the same 0xAARRGGBB layout, so no per-pixel work. */
private class ArgbImageFactory(
    private val key: Any, private val factory: suspend () -> ArgbImage?
) : ImageSource.ImageFactory {
    override suspend fun createImage(): BufferedImage? {
        val image = factory() ?: return null
        return BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also {
            it.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
        }
    }

    override fun equals(other: Any?) = other is ArgbImageFactory && key == other.key

    override fun hashCode() = key.hashCode()

    override fun toString() = "ArgbImageFactory($key)"
}
