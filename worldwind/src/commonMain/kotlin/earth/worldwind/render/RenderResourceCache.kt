package earth.worldwind.render

import dev.icerock.moko.resources.FileResource
import earth.worldwind.draw.DrawContext
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.AbsentResourceList
import earth.worldwind.util.LruMemoryCache
import kotlinx.coroutines.CoroutineScope

expect class RenderResourceCache: LruMemoryCache<Any, RenderResource> {
    val mainScope: CoroutineScope
    val absentResourceList: AbsentResourceList<Int>
    fun cancel()
    fun incAge()
    fun releaseEvictedResources(dc: DrawContext)
    fun retrieveTextFile(fileResource: FileResource, result: (String) -> Unit)
    fun retrieveTexture(imageSource: ImageSource, options: ImageOptions?): Texture?
}