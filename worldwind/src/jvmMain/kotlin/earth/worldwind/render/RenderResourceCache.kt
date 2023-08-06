package earth.worldwind.render

import dev.icerock.moko.resources.FileResource
import earth.worldwind.draw.DrawContext
import earth.worldwind.render.image.ImageDecoder
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.AbsentResourceList
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.log
import earth.worldwind.util.LruMemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

actual open class RenderResourceCache @JvmOverloads constructor(
    capacity: Long, lowWater: Long = (capacity * 0.75).toLong()
) : LruMemoryCache<Any, RenderResource>(capacity, lowWater) {
    override var age = 0L // Manually incrementable cache age
    /**
     * Main render resource retrieval scope
     */
    actual val mainScope = MainScope()
    /**
     * Identifies requested resources that whose retrieval failed.
     */
    actual val absentResourceList = AbsentResourceList<Int>(3, 60.seconds)
    val imageDecoder = ImageDecoder()

    override fun clear() {
        super.clear()
        absentResourceList.clear()
        age = 0
    }

    actual fun cancel() = mainScope.coroutineContext.cancelChildren() // Cancel all async jobs but keep scope reusable

    actual fun incAge() { ++age }

    actual fun releaseEvictedResources(dc: DrawContext) {
        TODO("Not yet implemented")
    }

    actual fun retrieveTextFile(fileResource: FileResource, result: (String) -> Unit) {
        mainScope.launch(Dispatchers.IO) {
            try {
                result(fileResource.readText())
            } catch (e: Throwable) {
                log(ERROR, "Resource retrieval failed ($fileResource): ${e.message}")
            }
        }
    }

    actual fun retrieveTexture(imageSource: ImageSource, options: ImageOptions?): Texture? {
        TODO("Not yet implemented")
    }
}