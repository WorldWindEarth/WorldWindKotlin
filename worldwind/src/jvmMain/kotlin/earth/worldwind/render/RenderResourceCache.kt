package earth.worldwind.render

import dev.icerock.moko.resources.AssetResource
import dev.icerock.moko.resources.FileResource
import dev.icerock.moko.resources.ResourceContainer
import earth.worldwind.WorldWind
import earth.worldwind.draw.DrawContext
import earth.worldwind.render.image.ImageDecoder
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import javax.imageio.ImageIO
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
import earth.worldwind.util.kgl.*
import kotlinx.coroutines.*
import java.awt.image.BufferedImage
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

actual open class RenderResourceCache @JvmOverloads constructor(
    capacity: Long = recommendedCapacity(),
    lowWater: Long = (capacity * 0.75).toLong()
) : LruMemoryCache<Any, RenderResource>(capacity, lowWater) {
    companion object {
        @JvmStatic
        fun recommendedCapacity(): Long {
            val maxMemory = Runtime.getRuntime().maxMemory().coerceAtLeast(256L * 1024 * 1024)
            return (maxMemory / 16L) * 3L
        }
    }

    var remoteRetrievalQueueSize = 8
    var localRetrievalQueueSize = 16
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
    protected val evictionQueue = ConcurrentLinkedQueue<RenderResource>()
    protected val lanes = RetrievalLanes<ImageSource>()

    override fun clear() {
        super.clear()
        evictionQueue.clear()
        lanes.clear()
        absentResourceList.clear()
        age = 0
    }

    actual fun incAge() {
        ++age
    }

    override fun entryRemoved(key: Any, oldValue: RenderResource, newValue: RenderResource?, evicted: Boolean) {
        evictionQueue.offer(oldValue)
    }

    actual fun releaseEvictedResources(dc: DrawContext) {
        val budgetMark = TimeSource.Monotonic.markNow()
        while (budgetMark.elapsedNow() < MAX_EVICTION_TIME_PER_FRAME) {
            val evicted = evictionQueue.poll() ?: break
            try {
                evicted.release(dc)
                if (isLoggable(DEBUG)) log(DEBUG, "Released render resource '$evicted'")
            } catch (e: Exception) {
                if (isLoggable(ERROR)) log(ERROR, "Exception releasing render resource '$evicted'", e)
            }
        }
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

    actual fun retrieveTextAsset(assetResource: AssetResource, result: (String) -> Unit) {
        mainScope.launch(Dispatchers.IO) {
            try {
                result(assetResource.readText())
            } catch (e: Throwable) {
                log(ERROR, "Asset retrieval failed ($assetResource): ${e.message}")
            }
        }
    }

    actual fun imageSourceFromAssetPath(
        assets: ResourceContainer<AssetResource>, path: String
    ): ImageSource? = ImageSource.fromImageFactory(
        object : ImageSource.ImageFactory {
            override suspend fun createImage() = runCatching {
                Thread.currentThread().contextClassLoader?.getResourceAsStream("assets/$path")?.use { ImageIO.read(it) }
            }.getOrNull()
        }
    )

    actual fun retrieveTexture(imageSource: ImageSource, options: ImageOptions?): Texture? {
        when {
            imageSource.isImage -> {
                // In-memory images can be uploaded immediately.
                return createTexture(options, imageSource.asImage()).also {
                    put(imageSource, it, it.byteCount)
                }
            }
            imageSource.isImageFactory -> {
                val factory = imageSource.asImageFactory()
                if (factory.isRunBlocking) {
                    return runBlocking {
                        factory.createImage()?.let { image ->
                            createTexture(options, image).also { texture -> put(imageSource, texture, texture.byteCount) }
                        }
                    }
                }
                // A cache-backed remote source (map tile, cached remote icon, any server-fetched
                // texture) retrieves in two phases: a cache-only read on the local lane, escalating
                // to a network fetch on the remote lane only on a miss. Once the cache read misses,
                // the source is marked so later frames skip straight to the network lane — that keeps
                // a congested remote lane from re-saturating the local lane and starving local
                // resources (see RetrievalLanes).
                if (factory is ImageSource.NetworkBoundImageFactory) {
                    when (lanes.planNetworkBound(imageSource, absentResourceList.isResourceAbsent(imageSource.hashCode()))) {
                        RetrievalPhase.NONE -> {}
                        // Phase 1: decode the cache-only view on the local lane. On miss, record it
                        // and escalate to the network lane in the same continuation.
                        RetrievalPhase.CACHE -> retrieve(imageSource, cacheView(imageSource), options, RetrievalLane.LOCAL) {
                            lanes.markChecked(imageSource)
                            retrieve(imageSource, imageSource, options, RetrievalLane.REMOTE)
                        }
                        // Phase 2: decode the original (network-fetching) source on the remote lane.
                        RetrievalPhase.NETWORK -> retrieve(imageSource, imageSource, options, RetrievalLane.REMOTE)
                    }
                    return null
                }
            }
        }

        // URLs use the remote lane; resources, files and plain factories use the local lane.
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
                // Run both the image decode and the pixel-format conversion (toBgraBytes inside ImageTexture
                // constructor) on Dispatchers.IO so that main thread does not stall.
                val texture = withContext(Dispatchers.IO) {
                    imageDecoder.decodeImage(decodeSource, options)?.let { createTexture(options, it) }
                }
                when {
                    texture != null -> retrievalSucceeded(imageSource, texture)
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
            // Runs synchronously after the local slot frees (no suspension point on the single-
            // threaded main scope), so the remote reservation can't race the local one.
            if (cacheMiss) onCacheMiss?.invoke()
        }
    }

    protected open fun createTexture(options: ImageOptions?, image: BufferedImage): Texture {
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
        lanes.unmarkChecked(source) // re-check the cache if this tile is re-requested after eviction
        WorldWind.requestRedraw()
        if (isLoggable(DEBUG)) log(DEBUG, "Image retrieval succeeded '$source'")
    }

    protected open fun retrievalFailed(source: ImageSource, ex: Throwable? = null) {
        absentResourceList.markResourceAbsent(source.hashCode(), !source.isUrl)
        lanes.unmarkChecked(source) // after the absent timeout, re-check the cache (a bulk download may have filled it)
        WorldWind.requestRedraw()
        when {
            ex is FileNotFoundException -> log(WARN, "Image not found '$source'")
            ex != null -> log(WARN, "Image retrieval failed with exception '$source': ${ex.message}")
            else -> log(WARN, "Image retrieval failed '$source'")
        }
    }
}