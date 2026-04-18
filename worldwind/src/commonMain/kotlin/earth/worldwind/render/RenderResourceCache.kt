package earth.worldwind.render

import dev.icerock.moko.resources.AssetResource
import dev.icerock.moko.resources.FileResource
import dev.icerock.moko.resources.ResourceContainer
import earth.worldwind.draw.DrawContext
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.AbsentResourceList
import earth.worldwind.util.LruMemoryCache
import kotlinx.coroutines.CoroutineScope

expect class RenderResourceCache: LruMemoryCache<Any, RenderResource> {
    val mainScope: CoroutineScope
    val absentResourceList: AbsentResourceList<Int>
    fun incAge()
    fun releaseEvictedResources(dc: DrawContext)
    fun retrieveTextFile(fileResource: FileResource, result: (String) -> Unit)
    fun retrieveTextAsset(assetResource: AssetResource, result: (String) -> Unit)
    fun retrieveTexture(imageSource: ImageSource, options: ImageOptions?): Texture?
    fun imageSourceFromAssetPath(assets : ResourceContainer<AssetResource>, path: String): ImageSource?
}