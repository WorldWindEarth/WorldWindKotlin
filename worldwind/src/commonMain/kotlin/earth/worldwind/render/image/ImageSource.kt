package earth.worldwind.render.image

import dev.icerock.moko.resources.ImageResource
import earth.worldwind.util.DownloadPostprocessor

expect class ImageSource {
    companion object {
        fun fromResource(imageResource: ImageResource): ImageSource
        fun fromUrlString(urlString: String, postprocessor: DownloadPostprocessor<*>? = null): ImageSource
        fun fromLineStipple(factor: Int, pattern: Short): ImageSource
        fun fromUnrecognized(source: Any): ImageSource
    }
}