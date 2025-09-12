package earth.worldwind.render.image

import dev.icerock.moko.resources.ImageResource
import earth.worldwind.util.AbstractSource

expect class ImageSource: AbstractSource {
    /**
     * Factory for delegating construction of images. WorldWind shapes configured with a ImageFactory construct
     * their images lazily, typically when the shape becomes visible on screen.
     */
    interface ImageFactory

    companion object {
        fun fromResource(imageResource: ImageResource): ImageSource
        fun fromUrlString(urlString: String): ImageSource
        fun fromImageFactory(factory: ImageFactory): ImageSource
        fun fromLineStipple(factor: Int, pattern: Short): ImageSource
        fun fromUnrecognized(source: Any): ImageSource
    }
}