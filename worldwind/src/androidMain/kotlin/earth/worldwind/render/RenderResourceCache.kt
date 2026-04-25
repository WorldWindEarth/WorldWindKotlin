package earth.worldwind.render

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import dev.icerock.moko.resources.AssetResource
import dev.icerock.moko.resources.FileResource
import dev.icerock.moko.resources.ResourceContainer
import earth.worldwind.WorldWind
import earth.worldwind.draw.DrawContext
import earth.worldwind.render.image.*
import earth.worldwind.util.AbsentResourceList
import earth.worldwind.util.Logger.DEBUG
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.INFO
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.isLoggable
import earth.worldwind.util.Logger.log
import earth.worldwind.util.LruMemoryCache
import earth.worldwind.util.kgl.*
import io.ktor.client.network.sockets.*
import kotlinx.coroutines.*
import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import kotlin.time.Duration.Companion.seconds

actual open class RenderResourceCache @JvmOverloads constructor(
    val context: Context, capacity: Long = recommendedCapacity(context), lowWater: Long = (capacity * 0.75).toLong()
): LruMemoryCache<Any, RenderResource>(capacity, lowWater), GLSurfaceView.EGLContextFactory {
    companion object {
        protected const val STALE_AGE = 300L
        protected const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        // Prefer GLES 3 (MSAA renderbuffers etc.); fall back to GLES 2 if unavailable.
        protected const val EGL_VERSION_3 = 3
        protected const val EGL_VERSION_2 = 2

        @JvmStatic
        fun recommendedCapacity(context: Context) =
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?)?.let {
                val mi = ActivityManager.MemoryInfo()
                it.getMemoryInfo(mi)
                mi.totalMem / 16 * 3 // use 3/16 of available system memory as recommended resource cache capacity
            } ?: (1024 * 1024 * 256) // use 256 MB by default
    }

    var remoteRetrievalQueueSize = 8
    var localRetrievalQueueSize = 16
    override var age = 0L // Manually incrementable cache age
    val imageDecoder = ImageDecoder(context)
    protected val evictionQueue = ConcurrentLinkedQueue<RenderResource>()
    protected val remoteRetrievals = mutableSetOf<ImageSource>()
    protected val localRetrievals = mutableSetOf<ImageSource>()
    protected val contextList = mutableListOf<EGLContext>()
    /**
     * Main render resource retrieval scope
     */
    actual val mainScope = MainScope()
    /**
     * Identifies requested resources that whose retrieval failed.
     */
    actual val absentResourceList = AbsentResourceList<Int>(3, 60.seconds)

    init {
        log(INFO, "RenderResourceCache initialized %,.0f KB".format(capacity / 1024f))
    }

    override fun clear() {
        super.clear()
        evictionQueue.clear() // the eviction queue no longer needs to be processed
        remoteRetrievals.clear()
        localRetrievals.clear()
        absentResourceList.clear()
        age = 0
    }

    override fun createContext(egl: EGL10, display: EGLDisplay, eglConfig: EGLConfig): EGLContext = synchronized(this) {
        val shareContext = if (contextList.isEmpty()) EGL10.EGL_NO_CONTEXT else contextList[0]
        if (contextList.isEmpty()) mainScope.launch { clear() } // Clear the render resource cache on the main thread.
        val attribList3 = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, EGL_VERSION_3, EGL10.EGL_NONE)
        var context = egl.eglCreateContext(display, eglConfig, shareContext, attribList3)
        if (context == null || context == EGL10.EGL_NO_CONTEXT) {
            val attribList2 = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, EGL_VERSION_2, EGL10.EGL_NONE)
            context = egl.eglCreateContext(display, eglConfig, shareContext, attribList2)
        }
        return context.also { contextList += it }
    }

    override fun destroyContext(egl: EGL10, display: EGLDisplay, context: EGLContext) = synchronized(this) {
        contextList -= context
        egl.eglDestroyContext(display, context)
        if (contextList.isEmpty()) {
            mainScope.coroutineContext.cancelChildren() // Cancel all async jobs but keep scope reusable
            mainScope.launch { clear() } // Clear the render resource cache; it's entries are now invalid.
        }
    }

    actual fun incAge() { ++age }

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

    /**
     * Release resources used later than [staleAge] frames ago
     *
     * @param staleAge Amount of frames to consider resource stale
     */
    @JvmOverloads
    fun trimStale(staleAge: Long = STALE_AGE) {
        val trimmedCapacity = trimToAge(age - staleAge * contextList.size)
        if (isLoggable(DEBUG)) log(DEBUG, "Trimmed resources to %,.0f KB".format(trimmedCapacity / 1024.0))
    }

    override fun entryRemoved(key: Any, oldValue: RenderResource, newValue: RenderResource?, evicted: Boolean) {
        evictionQueue.offer(oldValue)
    }

    actual fun retrieveTextFile(fileResource: FileResource, result: (String) -> Unit) {
        mainScope.launch(Dispatchers.IO) {
            try {
                result(fileResource.readText(context))
            } catch (e: Throwable) {
                log(ERROR, "Resource retrieval failed ($fileResource): ${e.message}")
            }
        }
    }

    actual fun retrieveTextAsset(assetResource: AssetResource, result: (String) -> Unit) {
        mainScope.launch(Dispatchers.IO) {
            try {
                result(assetResource.readText(context))
            } catch (e: Throwable) {
                log(ERROR, "Asset retrieval failed ($assetResource): ${e.message}")
            }
        }
    }

    actual fun imageSourceFromAssetPath(
        assets: ResourceContainer<AssetResource>, path: String
    ): ImageSource? = ImageSource.fromImageFactory(
        object : ImageSource.ImageFactory {
            override suspend fun createBitmap() = runCatching {
                BitmapFactory.decodeStream(context.assets.open(path))
            }.getOrNull()
        }
    )

    actual fun retrieveTexture(imageSource: ImageSource, options: ImageOptions?): Texture? {
        when {
            imageSource.isBitmap -> {
                // Bitmap image sources are already in memory, so a texture may be created and put into the cache immediately.
                // Do not recycle image sources from Bitmap on load to be able to reuse them after GL context lost
                return createTexture(options, imageSource.asBitmap(), recycleOnLoad = false).also {
                    put(imageSource, it, it.byteCount)
                }
            }
            imageSource.isImageFactory -> {
                val factory = imageSource.asImageFactory()
                if (factory.isRunBlocking) {
                    // Bitmap factory makes easy operations, so a texture may be created and put into the cache immediately.
                    return runBlocking {
                        factory.createBitmap()?.let { bitmap ->
                            createTexture(options, bitmap).also { put(imageSource, it, it.byteCount) }
                        }
                    }
                }
            }
        }

        // The image must be retrieved on a separate thread. Request the image source and return null to indicate that
        // the texture is not in memory. The image is added to the image retrieval cache upon successful retrieval. It's
        // then expected that a subsequent render frame will result in another call to retrieveTexture, in which case
        // the image will be found in the image retrieval cache.
        val currentRetrievals = if (imageSource.isUrl) remoteRetrievals else localRetrievals
        val retrievalQueueSize = if (imageSource.isUrl) remoteRetrievalQueueSize else localRetrievalQueueSize
        if (currentRetrievals.size < retrievalQueueSize && !currentRetrievals.contains(imageSource)
            // Ignore retrieval of resources marked as absent
            && !absentResourceList.isResourceAbsent(imageSource.hashCode())) {
            currentRetrievals += imageSource
            mainScope.launch {
                try {
                    imageDecoder.decodeImage(imageSource, options)?.also {
                        if (it.isRecycled) retrievalFailed(imageSource)
                        else retrievalSucceeded(imageSource, options, it)
                    } ?: retrievalFailed(imageSource)
                } catch (logged: Throwable) {
                    retrievalFailed(imageSource, logged)
                }
                finally {
                    currentRetrievals -= imageSource
                }
            }
        }
        return null
    }

    protected open fun createTexture(options: ImageOptions?, bitmap: Bitmap, recycleOnLoad: Boolean = true): Texture {
        val texture = BitmapTexture(bitmap, recycleOnLoad)
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

    protected open fun retrievalSucceeded(source: ImageSource, options: ImageOptions?, bitmap: Bitmap) {
        val texture = createTexture(options, bitmap)
        put(source, texture, texture.byteCount)
        absentResourceList.unmarkResourceAbsent(source.hashCode())
        WorldWind.requestRedraw()
        if (isLoggable(DEBUG)) log(DEBUG, "Image retrieval succeeded '$source'")
    }

    protected open fun retrievalFailed(source: ImageSource, ex: Throwable? = null) {
        absentResourceList.markResourceAbsent(source.hashCode(), !source.isUrl) // All local resources marked as absent permanently
        WorldWind.requestRedraw() // Try to load an alternate image source
        when {
            // log "socket timeout" exceptions while suppressing the stack trace
            ex is ConnectTimeoutException -> log(WARN, "Connect timeout retrieving image '$source'")
            ex is SocketTimeoutException -> log(WARN, "Socket timeout retrieving image '$source'")
            // log "file not found" exceptions while suppressing the stack trace
            ex is FileNotFoundException -> log(WARN, "Image not found '$source'")
            // log checked exceptions with the entire stack trace
            ex != null -> log(WARN, "Image retrieval failed with exception '$source': ${ex.message}")
            else -> log(WARN, "Image retrieval failed '$source'")
        }
    }
}