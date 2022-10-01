package earth.worldwind.globe.elevation

import earth.worldwind.util.AbstractSource
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import org.khronos.webgl.ArrayBufferView
import org.w3c.dom.url.URL

/**
 * Provides a mechanism for specifying elevations from a variety of sources.
 * <br>
 * ElevationSource supports following source types:
 * - Uniform Resource Locator [URL]
 * <br>
 * ElevationSource instances are intended to be used as a key into a cache or other data structure that enables sharing
 * of loaded elevation resources. File paths and URLs with the same string representation considered equals.
 */
actual open class ElevationSource protected constructor(source: Any): AbstractSource<ArrayBufferView>(source) {
    actual companion object {
        /**
         * Constructs an elevation source with an [URL].
         *
         * @param url Uniform Resource Locator
         *
         * @return the new elevation source
         */
        fun fromUrl(url: URL) = ElevationSource(url.href)

        /**
         * Constructs an elevation source with a URL string.
         *
         * @param urlString complete URL string
         *
         * @return the new elevation source
         */
        actual fun fromUrlString(urlString: String) = try {
            fromUrl(URL(urlString))
        } catch (e: Exception) {
            logMessage(ERROR, "ElevationSource", "fromUrlString", "invalidUrlString", e)
            throw e
        }

        /**
         * Constructs an elevation source with a generic [Any] instance. The source may be any non-null object. This is
         * equivalent to calling one of ElevationSource's type-specific factory methods when the source is a recognized type.
         *
         * @param source the generic source
         *
         * @return the new elevation source
         */
        actual fun fromUnrecognized(source: Any) = when(source) {
            is URL -> fromUrl(source)
            is String -> fromUrlString(source)
            else -> ElevationSource(source)
        }
    }

    /**
     * Indicates whether this elevation source is an [URL] string.
     */
    val isUrl get() = source is String

    /**
     * @return the source [URL]. Call [isUrl] to determine whether the source is an [URL] string.
     */
    fun asUrl() = source as String

    override fun toString() = when (source) {
        is String -> "URL: $source"
        else -> super.toString()
    }
}