package earth.worldwind.render.image

import dev.icerock.moko.resources.ImageResource
import earth.worldwind.util.AbstractSource

expect class ImageSource: AbstractSource {
    companion object {
        fun fromResource(imageResource: ImageResource): ImageSource
        fun fromUrlString(urlString: String): ImageSource
        fun fromLineStipple(factor: Int, pattern: Short): ImageSource
        fun fromUnrecognized(source: Any): ImageSource
    }
}