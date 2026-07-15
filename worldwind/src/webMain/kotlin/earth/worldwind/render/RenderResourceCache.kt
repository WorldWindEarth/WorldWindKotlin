package earth.worldwind.render

import dev.icerock.moko.resources.AssetResource
import dev.icerock.moko.resources.FileResource
import dev.icerock.moko.resources.ResourceContainer
import earth.worldwind.WorldWind
import earth.worldwind.draw.DrawContext
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ImageTexture
import earth.worldwind.render.image.ResamplingMode
import earth.worldwind.render.image.WrapMode
import earth.worldwind.util.AbsentResourceList
import earth.worldwind.util.Logger.DEBUG
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.isLoggable
import earth.worldwind.util.Logger.log
import earth.worldwind.util.LruMemoryCache
import earth.worldwind.util.RetrievalLane
import earth.worldwind.util.RetrievalLanes
import earth.worldwind.util.RetrievalPhase
import earth.worldwind.util.kgl.GL_NEAREST
import earth.worldwind.util.kgl.GL_REPEAT
import earth.worldwind.util.kgl.GL_TEXTURE_MAG_FILTER
import earth.worldwind.util.kgl.GL_TEXTURE_MIN_FILTER
import earth.worldwind.util.kgl.GL_TEXTURE_WRAP_S
import earth.worldwind.util.kgl.GL_TEXTURE_WRAP_T
import earth.worldwind.util.math.isPowerOfTwo
import earth.worldwind.util.math.powerOfTwoFloor
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.khronos.webgl.TexImageSource
import org.w3c.dom.CanvasImageSource
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.Image
import org.w3c.dom.ImageBitmap
import org.w3c.dom.url.URL
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.JsAny
import kotlin.js.unsafeCast
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

actual open class RenderResourceCache(
    capacity: Long = recommendedCapacity(), lowWater: Long = (capacity * 0.75).toLong()
) : LruMemoryCache<Any, RenderResource>(capacity, lowWater) {
    companion object {
        fun recommendedCapacity(): Long = window.navigator.unsafeCast<NavigatorWithDeviceMemory>().deviceMemory
            ?.let { it.toLong() * 1024 * 1024 * 1024 / 16 * 3 } ?: (512L * 1024 * 1024) // 512 Mb as backup
    }

    override var age = 0L // Manually incrementable cache age
    var remoteRetrievalQueueSize = 8
    var localRetrievalQueueSize = 16
    /**
     * Main render resource retrieval scope
     */
    actual val mainScope = MainScope()
    /**
     * Identifies requested resources that whose retrieval failed.
     */
    actual val absentResourceList = AbsentResourceList<Int>(3, 60.seconds)
    protected val lanes = RetrievalLanes<ImageSource>()
    protected val evictionQueue = ArrayDeque<RenderResource>()

    override fun clear() {
        super.clear()
        evictionQueue.clear()
        lanes.clear()
        absentResourceList.clear()
        age = 0
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

    actual fun retrieveTexture(imageSource: ImageSource, options: ImageOptions?): Texture? {
        when {
            imageSource.isImage -> {
                // Following type of image sources is already in memory, so a texture may be created and put into the cache immediately.
                return createTexture(options, imageSource.asImage())?.also { put(imageSource, it, it.byteCount) }
            }
            imageSource.isImageFactory -> {
                // A cache-backed remote source (map tile, cached remote icon, any server-fetched
                // texture wrapped for caching) retrieves in two phases: a cache-only read on the
                // local lane, escalating to a network fetch on the remote lane only on a miss. Once
                // the cache read misses, the source is marked so later frames skip straight to the
                // network lane — that keeps a congested remote lane from re-saturating the local
                // lane and starving local resources (see RetrievalLanes).
                if (imageSource.asImageFactory() is ImageSource.NetworkBoundImageFactory) {
                    when (lanes.planNetworkBound(imageSource, absentResourceList.isResourceAbsent(imageSource.hashCode()))) {
                        RetrievalPhase.NONE -> {}
                        RetrievalPhase.CACHE -> retrieveImageFactory(imageSource, cacheView(imageSource), options, RetrievalLane.LOCAL) {
                            lanes.markChecked(imageSource)
                            retrieveImageFactory(imageSource, imageSource, options, RetrievalLane.REMOTE)
                        }
                        RetrievalPhase.NETWORK -> retrieveImageFactory(imageSource, imageSource, options, RetrievalLane.REMOTE)
                    }
                    return null
                }
            }
        }

        // Retrieve image source from URL or local resource. Request the image source and return null to indicate that
        // the texture is not in memory. The image is added to the image retrieval cache upon successful retrieval. It's
        // then expected that a subsequent render frame will result in another call to retrieveTexture, in which case
        // the image will be found in the image retrieval cache. Remote URLs use the remote lane; local resources and
        // plain (non-network) factories use the local lane. Each callee self-guards its lane budget + absent list.
        when {
            imageSource.isUrl -> retrieveRemoteImage(RetrievalLane.REMOTE, imageSource, options, imageSource.asUrl())
            imageSource.isResource -> retrieveRemoteImage(RetrievalLane.LOCAL, imageSource, options, imageSource.asResource().fileUrl)
            imageSource.isImageFactory -> retrieveImageFactory(imageSource, imageSource, options, RetrievalLane.LOCAL)
        }
        return null
    }

    /**
     * A throwaway image source that decodes ONLY the local cache of a network-bound
     * source (`null` on a miss, never touching the network). Decoded by the normal pipeline so
     * the postprocessor (e.g. Mercator reprojection) still runs; the resulting texture is cached
     * under the original [imageSource] key by [retrieveImageFactory].
     */
    protected open fun cacheView(imageSource: ImageSource): ImageSource {
        val factory = imageSource.asImageFactory() as ImageSource.NetworkBoundImageFactory
        return ImageSource.fromImageFactory(object : ImageSource.ImageFactory {
            override suspend fun createImage() = factory.createCachedImage()
        }).also { it.postprocessor = imageSource.postprocessor }
    }

    /**
     * Launch a single image-factory retrieval on the chosen lane. [decodeSource] is what is
     * actually decoded (a [cacheView] for a cache phase, otherwise [imageSource] itself);
     * [imageSource] is always the canonical key for dedup, caching and the absent list. A
     * non-null [onCacheMiss] marks this as a cache phase: a `null` result then means "cache
     * miss" — it runs [onCacheMiss] after the local slot frees (escalating to the network) and
     * is NOT marked absent. Only a genuine network / local failure marks the source absent.
     */
    protected open fun retrieveImageFactory(
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
                val image = decodeSource.asImageFactory().createImage()
                when {
                    image != null -> {
                        // Apply image postprocessor (e.g. Mercator reprojection) before caching
                        val processed = decodeSource.postprocessor?.process(image) ?: image
                        retrievalSucceeded(imageSource, options, processed)
                    }
                    onCacheMiss != null -> cacheMiss = true // cache miss — escalate, do not mark absent
                    else -> retrievalFailed(imageSource)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (onCacheMiss != null) cacheMiss = true else {
                    log(WARN, "Image retrieval failed ($imageSource): ${e.message}")
                    retrievalFailed(imageSource)
                }
            } finally {
                lanes.release(lane, imageSource)
            }
            // Runs synchronously after the slot frees (JS is single-threaded), so the remote
            // reservation can't race the local one.
            if (cacheMiss) onCacheMiss?.invoke()
        }
    }

    actual fun retrieveTextFile(fileResource: FileResource, result: (String) -> Unit) {
        mainScope.launch {
            try {
                result(fileResource.getText())
            } catch (e: Throwable) {
                log(ERROR, "Resource retrieval failed ($fileResource): ${e.message}")
            }
        }
    }

    actual fun retrieveTextAsset(assetResource: AssetResource, result: (String) -> Unit) {
        mainScope.launch {
            try {
                result(assetResource.getText())
            } catch (e: Throwable) {
                log(ERROR, "Asset retrieval failed ($assetResource): ${e.message}")
            }
        }
    }

    actual fun imageSourceFromAssetPath(assets: ResourceContainer<AssetResource>, path: String): ImageSource? =
        assets.values().firstOrNull { it.rawPath == path }?.let { ImageSource.fromUrlString(it.originalPath) }

    protected open fun retrieveRemoteImage(
        lane: RetrievalLane, imageSource: ImageSource, options: ImageOptions?, src: String
    ) {
        val queueSize = if (lane == RetrievalLane.REMOTE) remoteRetrievalQueueSize else localRetrievalQueueSize
        if (!lanes.canReserve(lane, queueSize, imageSource)
            || absentResourceList.isResourceAbsent(imageSource.hashCode())) return
        val image = Image()
        var postprocessorExecuted = false
        image.onload = {
            // Check if image postprocessor is assigned and not yet executed.
            // OnLoad event can be called second time by reassigning image.src inside postprocessor.
            val postprocessor = imageSource.postprocessor
            if (postprocessor != null && !postprocessorExecuted) {
                postprocessorExecuted = true // Prevent cyclic processing due to src modification inside postprocessing.
                mainScope.launch {
                    try {
                        postprocessor.process(image)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        log(WARN, "Image postprocessor failed ($imageSource): ${e.message}")
                        retrievalFailed(imageSource)
                        lanes.release(lane, imageSource)
                    }
                }
            } else {
                retrievalSucceeded(imageSource, options, image) // Consume original or processed image as retrieved
                lanes.release(lane, imageSource)
            }
            if (postprocessor != null) URL.revokeObjectURL(image.src) // Revoke URL possibly created in postprocessor
        }
        image.onerror = { _, _, _, _, _ ->
            retrievalFailed(imageSource)
            lanes.release(lane, imageSource)
            null
        }
        lanes.reserve(lane, imageSource)
        image.crossOrigin = "anonymous"
        image.src = src
    }

    protected open fun createTexture(options: ImageOptions?, image: TexImageSource): Texture? {
        var (width, height) = when (image) {
            is HTMLImageElement -> image.width to image.height
            is HTMLCanvasElement -> image.width to image.height
            is ImageBitmap -> image.width to image.height
            else -> return null
        }

        // Process initialWidth and initialHeight if specified
        if (width == 0 || height == 0) {
            // If source image has dimensions, then resize it proportionally to fit initial size restrictions
            val ratioW = if (options != null && options.initialWidth > 0) width / options.initialWidth else 0
            val ratioH = if (options != null && options.initialHeight > 0) height / options.initialHeight else 0
            val ratio = if (ratioH > ratioW) ratioH else ratioW
            if (ratio > 0) {
                width /= ratio
                height /= ratio
            }
        } else if (options != null && options.initialWidth > 0 && options.initialHeight > 0) {
            // If source image has no dimensions (e.g. SVG image), then set initial size of image
            width = options.initialWidth
            height = options.initialHeight
        }

        // Cap the texture to maxDimension (downscaled via canvas) to bound memory for large atlases
        val cap = options?.maxDimension ?: 0
        val capped = cap > 0 && maxOf(width, height) > cap
        if (capped) {
            val scale = cap.toDouble() / maxOf(width, height)
            width = maxOf(1, (width * scale).toInt())
            height = maxOf(1, (height * scale).toInt())
        }

        // Create image texture and apply texture parameters
        val resize = capped || (options?.wrapMode == WrapMode.REPEAT && !(isPowerOfTwo(width) && isPowerOfTwo(height)))
        val texture = if (resize) resizeImage(image, width, height).let { ImageTexture(it, it.width, it.height) }
        else ImageTexture(image, width, height)
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

    protected open fun retrievalSucceeded(source: ImageSource, options: ImageOptions?, image: TexImageSource) {
        // Create texture and put it into cache.
        createTexture(options, image)?.let { put(source, it, it.byteCount) }
        absentResourceList.unmarkResourceAbsent(source.hashCode())
        lanes.unmarkChecked(source) // re-check the cache if this source is re-requested after eviction
        WorldWind.requestRedraw()
        if (isLoggable(DEBUG)) log(DEBUG, "Image retrieval succeeded: $source")
    }

    protected open fun retrievalFailed(source: ImageSource) {
        absentResourceList.markResourceAbsent(source.hashCode())
        lanes.unmarkChecked(source) // after the absent timeout, re-check the cache (a bulk download may have filled it)
        WorldWind.requestRedraw() // re-issue requests a full retrieval lane dropped, like the other platforms
        log(WARN, "Image retrieval failed: $source")
    }

    protected open fun resizeImage(image: CanvasImageSource, width: Int, height: Int): HTMLCanvasElement {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = powerOfTwoFloor(width)
        canvas.height = powerOfTwoFloor(height)
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.drawImage(image, 0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble())
        return canvas
    }
}

/** Typed view of `navigator.deviceMemory` (Chrome / Edge / Opera; unsupported on Safari /
 *  Firefox where the property is absent and reads as `undefined` → null at the Kotlin call site). */
private external interface NavigatorWithDeviceMemory : JsAny {
    val deviceMemory: Double?
}