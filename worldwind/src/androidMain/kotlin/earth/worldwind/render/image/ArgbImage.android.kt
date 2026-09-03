package earth.worldwind.render.image

import android.graphics.Bitmap

actual fun argbImageSource(key: Any, factory: suspend () -> ArgbImage?): ImageSource =
    ImageSource.fromImageFactory(ArgbImageFactory(key, factory))

/** Android [ImageSource.ImageFactory] materializing [ArgbImage] pixels as an ARGB_8888
 *  [Bitmap]. A fresh bitmap is built per call, as the factory contract requires — the
 *  pixels are re-decoded or re-read from the retained int array, never handed out twice. */
private class ArgbImageFactory(
    private val key: Any, private val factory: suspend () -> ArgbImage?
) : ImageSource.ImageFactory {
    override suspend fun createBitmap(): Bitmap? {
        val image = factory() ?: return null
        return Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
    }

    override fun equals(other: Any?) = other is ArgbImageFactory && key == other.key

    override fun hashCode() = key.hashCode()

    override fun toString() = "ArgbImageFactory($key)"
}
