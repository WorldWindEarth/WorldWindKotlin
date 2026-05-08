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
import kotlin.time.Duration.Companion.seconds

/**
 * iOS render-resource cache with the async retrieval pipeline wired up. Mirrors the JVM
 * version's structure: separate retrieval queues for remote (URL) vs local (resource/file)
 * sources, an absent-resource list to suppress repeated retries on missing tiles, and an
 * eviction queue so GL deletes happen on the GL thread (`releaseEvictedResources` called
 * from `WorldWindow.doFrame`).
 */
actual open class RenderResourceCache(
    capacity: Long = DEFAULT_CAPACITY,
    lowWater: Long = (capacity * 0.75).toLong()
) : LruMemoryCache<Any, RenderResource>(capacity, lowWater) {
    companion object {
        const val DEFAULT_CAPACITY = 512L * 1024 * 1024
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
    protected val remoteRetrievals = mutableSetOf<ImageSource>()
    protected val localRetrievals = mutableSetOf<ImageSource>()

    override fun clear() {
        super.clear()
        evictionQueue.clear()
        remoteRetrievals.clear()
        localRetrievals.clear()
        absentResourceList.clear()
    }

    actual fun incAge() { ++age }

    override fun entryRemoved(key: Any, oldValue: RenderResource, newValue: RenderResource?, evicted: Boolean) {
        evictionQueue.addLast(oldValue)
    }

    actual fun releaseEvictedResources(dc: DrawContext) {
        while (evictionQueue.isNotEmpty()) {
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
            override suspend fun createImage(): platform.UIKit.UIImage? {
                val filePath = asset.bundle.pathForResource(asset.fileName, asset.extension)
                    ?: return null
                return platform.UIKit.UIImage.imageWithContentsOfFile(filePath)
            }
        })
    }

    actual fun retrieveTexture(imageSource: ImageSource, options: ImageOptions?): Texture? {
        val currentRetrievals = if (imageSource.isUrl) remoteRetrievals else localRetrievals
        val retrievalQueueSize = if (imageSource.isUrl) remoteRetrievalQueueSize else localRetrievalQueueSize
        if (currentRetrievals.size < retrievalQueueSize
            && !currentRetrievals.contains(imageSource)
            && !absentResourceList.isResourceAbsent(imageSource.hashCode())
        ) {
            currentRetrievals += imageSource
            mainScope.launch {
                try {
                    val image = imageDecoder.decodeImage(imageSource, options)
                    if (image != null) {
                        retrievalSucceeded(imageSource, createTexture(options, image))
                    } else {
                        retrievalFailed(imageSource)
                    }
                } catch (logged: Throwable) {
                    retrievalFailed(imageSource, logged)
                } finally {
                    currentRetrievals -= imageSource
                }
            }
        }
        return null
    }

    protected open fun createTexture(
        options: ImageOptions?, image: earth.worldwind.render.image.ImageData
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
        WorldWind.requestRedraw()
        if (isLoggable(DEBUG)) log(DEBUG, "Image retrieval succeeded '$source'")
    }

    protected open fun retrievalFailed(source: ImageSource, ex: Throwable? = null) {
        // Local sources (resource/file) get the "permanently missing" flag; URL sources
        // are only marked transient so retries can fire after the absent-list timeout.
        absentResourceList.markResourceAbsent(source.hashCode(), !source.isUrl)
        WorldWind.requestRedraw()
        when {
            ex != null -> log(WARN, "Image retrieval failed '$source': ${ex.message}")
            else -> log(WARN, "Image retrieval failed '$source'")
        }
    }
}

