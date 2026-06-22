package earth.worldwind.render

import dev.icerock.moko.resources.AssetResource
import dev.icerock.moko.resources.FileResource
import dev.icerock.moko.resources.ResourceContainer
import earth.worldwind.draw.DrawContext
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.AbsentResourceList
import earth.worldwind.util.LruMemoryCache
import kotlin.time.Duration.Companion.milliseconds
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

/** Per-frame GL-thread time budget for [RenderResourceCache.releaseEvictedResources].
 *  Each release fires a `glDeleteTexture` or `glDeleteBuffer` — both ~1-10 ms on Mali /
 *  Adreno from the driver `munmap` of GPU storage. The budget bounds the GL-thread stall
 *  the eviction loop can introduce per frame in a way that adapts to GPU speed; on a fast
 *  GPU more deletes complete in budget, on a slow GPU fewer.
 *
 *  Cesium's renderers throttle tile-cache unloads with the same ~5 ms pattern.
 *  Items past the budget stay in the eviction queue for next frame — they have no live
 *  references, just hold GL handles a little longer. */
internal val MAX_EVICTION_TIME_PER_FRAME = 5.milliseconds