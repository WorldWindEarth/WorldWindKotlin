package earth.worldwind.render

import dev.icerock.moko.resources.AssetResource
import dev.icerock.moko.resources.FileResource
import dev.icerock.moko.resources.ResourceContainer
import earth.worldwind.WorldWind
import earth.worldwind.draw.DrawContext
import earth.worldwind.render.image.ImageDecoder
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ImageTexture
import earth.worldwind.render.image.ResamplingMode
import earth.worldwind.render.image.WrapMode
import earth.worldwind.util.AbsentResourceList
import earth.worldwind.util.RetrievalLane
import earth.worldwind.util.RetrievalLanes
import earth.worldwind.util.RetrievalPhase
import earth.worldwind.util.Logger.DEBUG
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.isLoggable
import earth.worldwind.util.Logger.log
import earth.worldwind.util.LruMemoryCache
import earth.worldwind.util.kgl.GL_NEAREST
import earth.worldwind.util.kgl.GL_REPEAT
import earth.worldwind.util.kgl.GL_TEXTURE_MAG_FILTER
import earth.worldwind.util.kgl.GL_TEXTURE_MIN_FILTER
import earth.worldwind.util.kgl.GL_TEXTURE_WRAP_S
import earth.worldwind.util.kgl.GL_TEXTURE_WRAP_T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import earth.worldwind.render.image.ImageData
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIImage

/**
 * iOS render-resource cache with the async retrieval pipeline wired up. Mirrors the JVM
 * version's structure: separate retrieval queues for remote (URL) vs local (resource/file)
 * sources, an absent-resource list to suppress repeated retries on missing tiles, and an
 * eviction queue so GL deletes happen on the GL thread (`releaseEvictedResources` called
 * from `WorldWindow.doFrame`).
 */
actual open class RenderResourceCache(
    capacity: Long = recommendedCapacity(),
    lowWater: Long = (capacity * 0.75).toLong()
) : LruMemoryCache<Any, RenderResource>(capacity, lowWater) {
    companion object {
        // Use 3/16 of physical memory as recommended resource cache capacity, same as Android.
        fun recommendedCapacity(): Long = (NSProcessInfo.processInfo.physicalMemory / 16UL * 3UL).toLong()
    }

    override var age = 0L
    actual val mainScope: CoroutineScope = MainScope()
    actual val absentResourceList = AbsentResourceList<Int>(3, 60.seconds)

    open val imageDecoder = ImageDecoder()

    /** Maximum concurrent retrievals per source category. Tuned conservatively: more than
     *  this in flight per category just thrashes the cache and buffers more half-decoded
     *  images. Increase for high-throughput tile streams; the GL thread is still the
     *  serialization point at upload time. */
    var remoteRetrievalQueueSize = 8
    var localRetrievalQueueSize = 16

    protected val evictionQueue = ArrayDeque<RenderResource>()
    protected val lanes = RetrievalLanes<ImageSource>()

    override fun clear() {
        super.clear()
        evictionQueue.clear()
        lanes.clear()
        absentResourceList.clear()
    }

    actual fun incAge() { ++age }

    override fun entryRemoved(key: Any, oldValue: RenderResource, newValue: RenderResource?, evicted: Boolean) {
        evictionQueue.addLast(oldValue)
    }

    actual fun releaseEvictedResources(dc: DrawContext) {
        val budgetMark = TimeSource.Monotonic.markNow()
        while (budgetMark.elapsedNow() < MAX_EVICTION_TIME_PER_FRAME && evictionQueue.isNotEmpty()) {
            val evicted = evictionQueue.removeFirst()
            try {
                evicted.release(dc)
                if (isLoggable(DEBUG)) log(DEBUG, "Released render resource '$evicted'")
            } catch (e: Exception) {
                if (isLoggable(ERROR)) log(ERROR, "Exception releasing render resource '$evicted'", e)
            }
        }
    }

    actual fun retrieveTextFile(fileResource: FileResource, result: (String) -> Unit) {
        // moko's iOS FileResource.readText() is a suspend extension that resolves the file
        // from the bundle and reads it as UTF-8. Run on the main coroutine; moko's iOS
        // implementation already hops to a background queue for I/O.
        mainScope.launch {
            try {
                result(fileResource.readText())
            } catch (e: Throwable) {
                log(ERROR, "FileResource retrieval failed ($fileResource): ${e.message}")
            }
        }
    }

    actual fun retrieveTextAsset(assetResource: AssetResource, result: (String) -> Unit) {
        // moko's iOS AssetResource carries the bundle + relative path. `readText()` works
        // on iOS as a suspend extension — same shape as `FileResource.readText()`.
        mainScope.launch {
            try {
                result(assetResource.readText())
            } catch (e: Throwable) {
                log(ERROR, "AssetResource retrieval failed ($assetResource): ${e.message}")
            }
        }
    }

    actual fun imageSourceFromAssetPath(
        assets: ResourceContainer<AssetResource>, path: String
    ): ImageSource? {
        // Look up the moko AssetResource whose `originalPath` matches the requested path
        // (e.g. ColladaScene resolves a texture reference like "collada/duckCM.png" against
        // the model's :worldwind-tutorials asset bundle). Without this hook,
        // ColladaScene.imageSourceFactory returns null and the engine falls through to a
        // network fetch on `localhost`, which is what users see as
        // "HTTP load failed Error -1004" in the iOS console.
        val asset = assets.values().firstOrNull { it.originalPath == path } ?: return null
        return ImageSource.fromImageFactory(object : ImageSource.ImageFactory {
            override suspend fun createImage(): UIImage? {
                val filePath = asset.bundle.pathForResource(asset.fileName, asset.extension)
                    ?: return null
                return UIImage.imageWithContentsOfFile(filePath)
            }
        })
    }

    actual fun retrieveTexture(imageSource: ImageSource, options: ImageOptions?): Texture? {
        // A cache-backed remote source (map tile, cached remote icon, any server-fetched texture)
        // retrieves in two phases: a cache-only read on the local lane, escalating to a network
        // fetch on the remote lane only on a miss. Once the cache read misses, the source is marked
        // so later frames skip straight to the network lane — that keeps a congested remote lane
        // from re-saturating the local lane and starving local resources (see RetrievalLanes).
        if (imageSource.isImageFactory && imageSource.asImageFactory() is ImageSource.NetworkBoundImageFactory) {
            when (lanes.planNetworkBound(imageSource, absentResourceList.isResourceAbsent(imageSource.hashCode()))) {
                RetrievalPhase.NONE -> {}
                // Phase 1: decode the cache-only view on the local lane. On miss, record it and
                // escalate to the network lane in the same continuation.
                RetrievalPhase.CACHE -> retrieve(imageSource, cacheView(imageSource), options, RetrievalLane.LOCAL) {
                    lanes.markChecked(imageSource)
                    retrieve(imageSource, imageSource, options, RetrievalLane.REMOTE)
                }
                // Phase 2: decode the original (network-fetching) source on the remote lane.
                RetrievalPhase.NETWORK -> retrieve(imageSource, imageSource, options, RetrievalLane.REMOTE)
            }
            return null
        }

        // URLs use the remote lane; resources and plain factories use the local lane.
        retrieve(imageSource, imageSource, options, if (imageSource.isUrl) RetrievalLane.REMOTE else RetrievalLane.LOCAL)
        return null
    }

    /**
     * A throwaway image source that decodes ONLY the local cache of a network-bound
     * source (`null` on a miss, never touching the network). Decoded by the normal pipeline so
     * the postprocessor (e.g. Mercator reprojection) still runs; the resulting texture is cached
     * under the original [imageSource] key by [retrieve].
     */
    protected open fun cacheView(imageSource: ImageSource): ImageSource {
        val factory = imageSource.asImageFactory() as ImageSource.NetworkBoundImageFactory
        return ImageSource.fromImageFactory(object : ImageSource.ImageFactory {
            override suspend fun createImage() = factory.createCachedImage()
        }).also { it.postprocessor = imageSource.postprocessor }
    }

    /**
     * Launch a single retrieval on the chosen lane. [decodeSource] is what the decoder actually
     * reads (a [cacheView] for a cache phase, otherwise [imageSource] itself); [imageSource] is
     * always the canonical key for dedup, caching and the absent list. A non-null [onCacheMiss]
     * marks this as a cache phase: a `null` decode result then means "cache miss" — it runs
     * [onCacheMiss] after the local slot frees (escalating to the network) and is NOT marked
     * absent. Only a genuine network / local failure marks the source absent.
     */
    protected open fun retrieve(
        imageSource: ImageSource,
        decodeSource: ImageSource,
        options: ImageOptions?,
        lane: RetrievalLane,
        onCacheMiss: (() -> Unit)? = null,
    ) {
        val queueSize = if (lane == RetrievalLane.REMOTE) remoteRetrievalQueueSize else localRetrievalQueueSize
        if (!lanes.canReserve(lane, queueSize, imageSource)
            || absentResourceList.isResourceAbsent(imageSource.hashCode())) return
        lanes.reserve(lane, imageSource)
        mainScope.launch {
            var cacheMiss = false
            try {
                val image = imageDecoder.decodeImage(decodeSource, options)
                when {
                    image != null -> retrievalSucceeded(imageSource, createTexture(options, image))
                    onCacheMiss != null -> cacheMiss = true // cache miss — escalate, do not mark absent
                    else -> retrievalFailed(imageSource)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled // cooperative cancellation (e.g. GL context loss) must not escalate or mark absent
            } catch (logged: Throwable) {
                if (onCacheMiss != null) cacheMiss = true else retrievalFailed(imageSource, logged)
            } finally {
                lanes.release(lane, imageSource)
            }
            // Runs synchronously after the local slot frees (single-threaded main scope), so the
            // remote reservation can't race the local one.
            if (cacheMiss) onCacheMiss?.invoke()
        }
    }

    protected open fun createTexture(
        options: ImageOptions?, image: ImageData
    ): Texture {
        val texture = ImageTexture(image)
        if (options?.resamplingMode == ResamplingMode.NEAREST_NEIGHBOR) {
            texture.setTexParameter(GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            texture.setTexParameter(GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        }
        if (options?.wrapMode == WrapMode.REPEAT) {
            texture.setTexParameter(GL_TEXTURE_WRAP_S, GL_REPEAT)
            texture.setTexParameter(GL_TEXTURE_WRAP_T, GL_REPEAT)
        }
        return texture
    }

    protected open fun retrievalSucceeded(source: ImageSource, texture: Texture) {
        put(source, texture, texture.byteCount)
        absentResourceList.unmarkResourceAbsent(source.hashCode())
        lanes.unmarkChecked(source) // re-check the cache if this source is re-requested after eviction
        WorldWind.requestRedraw()
        if (isLoggable(DEBUG)) log(DEBUG, "Image retrieval succeeded '$source'")
    }

    protected open fun retrievalFailed(source: ImageSource, ex: Throwable? = null) {
        // Local sources (resource/file) get the "permanently missing" flag; URL sources
        // are only marked transient so retries can fire after the absent-list timeout.
        absentResourceList.markResourceAbsent(source.hashCode(), !source.isUrl)
        lanes.unmarkChecked(source) // after the absent timeout, re-check the cache (a bulk download may have filled it)
        WorldWind.requestRedraw()
        when {
            ex != null -> log(WARN, "Image retrieval failed '$source': ${ex.message}")
            else -> log(WARN, "Image retrieval failed '$source'")
        }
    }
}

