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
    protected val remoteRetrievals = mutableSetOf<ImageSource>()
    protected val localRetrievals = mutableSetOf<ImageSource>()

    override fun clear() {
        super.clear()
        evictionQueue.clear()
        remoteRetrievals.clear()
        localRetrievals.clear()
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
        while (true) {
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
            }
        }

        val currentRetrievals = if (imageSource.isUrl) remoteRetrievals else localRetrievals
        val retrievalQueueSize = if (imageSource.isUrl) remoteRetrievalQueueSize else localRetrievalQueueSize
        if (currentRetrievals.size < retrievalQueueSize && !currentRetrievals.contains(imageSource)
            && !absentResourceList.isResourceAbsent(imageSource.hashCode())
        ) {
            currentRetrievals += imageSource
            mainScope.launch {
                try {
                    // Run both the image decode and the pixel-format conversion (toBgraBytes inside ImageTexture
                    // constructor) on Dispatchers.IO so that main thread does not stall.
                    val texture = withContext(Dispatchers.IO) {
                        imageDecoder.decodeImage(imageSource, options)?.let { createTexture(options, it) }
                    }
                    if (texture != null) retrievalSucceeded(imageSource, texture)
                    else retrievalFailed(imageSource)
                } catch (logged: Throwable) {
                    retrievalFailed(imageSource, logged)
                } finally {
                    currentRetrievals -= imageSource
                }
            }
        }
        return null
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
        WorldWind.requestRedraw()
        if (isLoggable(DEBUG)) log(DEBUG, "Image retrieval succeeded '$source'")
    }

    protected open fun retrievalFailed(source: ImageSource, ex: Throwable? = null) {
        absentResourceList.markResourceAbsent(source.hashCode(), !source.isUrl)
        WorldWind.requestRedraw()
        when {
            ex is FileNotFoundException -> log(WARN, "Image not found '$source'")
            ex != null -> log(WARN, "Image retrieval failed with exception '$source': ${ex.message}")
            else -> log(WARN, "Image retrieval failed '$source'")
        }
    }
}