package earth.worldwind.render

import dev.icerock.moko.resources.FileResource
import earth.worldwind.WorldWind
import earth.worldwind.draw.DrawContext
import earth.worldwind.render.image.*
import earth.worldwind.util.AbsentResourceList
import earth.worldwind.util.Logger.DEBUG
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.isLoggable
import earth.worldwind.util.Logger.log
import earth.worldwind.util.LruMemoryCache
import earth.worldwind.util.kgl.*
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.khronos.webgl.TexImageSource
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.Image
import org.w3c.dom.url.URL
import kotlin.time.Duration.Companion.seconds

actual open class RenderResourceCache(
    capacity: Long = recommendedCapacity(), lowWater: Long = (capacity * 0.75).toLong()
) : LruMemoryCache<Any, RenderResource>(capacity, lowWater) {
    companion object {
        fun recommendedCapacity(): Long = (window.navigator.asDynamic().deviceMemory as? Number)
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
    /**
     * List of remote URL retrievals currently in progress.
     */
    protected val remoteRetrievals = mutableSetOf<ImageSource>()
    /**
     * List of other local retrievals currently in progress.
     */
    protected val localRetrievals = mutableSetOf<ImageSource>()

    override fun clear() {
        super.clear()
        remoteRetrievals.clear()
        localRetrievals.clear()
        absentResourceList.clear()
        age = 0
    }

    actual fun incAge() { ++age }

    actual fun releaseEvictedResources(dc: DrawContext) {
        // TODO Implement evicted resources management
    }

    actual fun retrieveTexture(imageSource: ImageSource, options: ImageOptions?): Texture? {
        when {
            imageSource.isImage -> {
                // Following type of image sources is already in memory, so a texture may be created and put into the cache immediately.
                return createTexture(options, imageSource.asImage())?.also { put(imageSource, it, it.byteCount) }
            }
        }

        // Ignore retrieval of resources marked as absent
        if (absentResourceList.isResourceAbsent(imageSource.hashCode())) return null

        // Retrieve image source from URL or local resource. Request the image source and return null to indicate that
        // the texture is not in memory. The image is added to the image retrieval cache upon successful retrieval. It's
        // then expected that a subsequent render frame will result in another call to retrieveTexture, in which case
        // the image will be found in the image retrieval cache.
        val currentRetrievals = if (imageSource.isUrl) remoteRetrievals else localRetrievals
        val retrievalQueueSize = if (imageSource.isUrl) remoteRetrievalQueueSize else localRetrievalQueueSize
        if (currentRetrievals.size < retrievalQueueSize && !currentRetrievals.contains(imageSource)) {
            when {
                imageSource.isUrl -> retrieveRemoteImage(currentRetrievals, imageSource, options, imageSource.asUrl())
                imageSource.isResource -> retrieveRemoteImage(
                    currentRetrievals, imageSource, options, imageSource.asResource().fileUrl
                )
                imageSource.isImageFactory -> {
                    currentRetrievals += imageSource
                    mainScope.launch {
                        imageSource.asImageFactory().createImage()?.let { retrievalSucceeded(imageSource, options, it) }
                            ?: retrievalFailed(imageSource)
                        currentRetrievals -= imageSource
                    }
                }
            }
        }
        return  null
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

    protected open fun retrieveRemoteImage(
        currentRetrievals: MutableSet<ImageSource>, imageSource: ImageSource, options: ImageOptions?, src: String
    ) {
        val image = Image()
        var postprocessorExecuted = false
        image.onload = {
            // Check if image postprocessor is assigned and not yet executed.
            // OnLoad event can be called second time by reassigning image.src inside postprocessor.
            val postprocessor = imageSource.postprocessor
            if (postprocessor != null && !postprocessorExecuted) {
                postprocessorExecuted = true // Prevent cyclic processing due to src modification inside postprocessing.
                mainScope.launch { postprocessor.process(image) } // Apply image transformation.
            } else retrievalSucceeded(imageSource, options, image) // Consume original or processed image as retrieved
            if (postprocessor != null) URL.revokeObjectURL(image.src) // Revoke URL possibly created in postprocessor
            currentRetrievals -= imageSource
        }
        image.onerror = { _, _, _, _, _ ->
            retrievalFailed(imageSource)
            currentRetrievals -= imageSource
        }
        currentRetrievals += imageSource
        image.crossOrigin = "anonymous"
        image.src = src
    }

    protected open fun createTexture(options: ImageOptions?, image: TexImageSource): Texture? {
        var (width, height) = when (image) {
            is HTMLImageElement -> image.width to image.height
            is HTMLCanvasElement -> image.width to image.height
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

        // Create image texture and apply texture parameters
        val texture = ImageTexture(image, width, height)
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
        WorldWind.requestRedraw()
        if (isLoggable(DEBUG)) log(DEBUG, "Image retrieval succeeded: $source")
    }

    protected open fun retrievalFailed(source: ImageSource) {
        absentResourceList.markResourceAbsent(source.hashCode())
        log(WARN, "Image retrieval failed: $source")
    }
}