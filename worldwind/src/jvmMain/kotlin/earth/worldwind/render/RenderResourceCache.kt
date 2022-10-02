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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

actual open class RenderResourceCache @JvmOverloads constructor(
    actual val mainScope: CoroutineScope, capacity: Long, lowWater: Long = (capacity * 0.75).toLong()
) : LruMemoryCache<Any, RenderResource>(capacity, lowWater) {
    override var age = 0L // Manually incrementable cache age
    /**
     * Identifies requested resources that whose retrieval failed.
     */
    actual val absentResourceList = AbsentResourceList<Int>(3, 60.seconds)
    val imageDecoder = ImageDecoder()

    override fun clear() {
        entries.clear() // the cache entries are invalid; clear but don't call entryRemoved
        absentResourceList.clear()
        usedCapacity = 0
    }

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