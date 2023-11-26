package earth.worldwind.globe.elevation

import earth.worldwind.util.AbstractSource
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.ResourcePostprocessor
import java.io.File
import java.net.MalformedURLException
import java.net.URL
import java.nio.Buffer

/**
 * Provides a mechanism for specifying elevations from a variety of sources.
 * <br>
 * ElevationSource supports following source types:
 * - Uniform Resource Locator [URL]
 * - Local image [File]
 * - WorldWind [ElevationSource.ElevationDataFactory]
 * <br>
 * ElevationSource instances are intended to be used as a key into a cache or other data structure that enables sharing
 * of loaded elevation resources. File paths and URLs with the same string representation considered equals.
 */
actual open class ElevationSource protected constructor(source: Any): AbstractSource(source) {
    actual companion object {
        /**
         * Constructs an elevation source with a [ElevationDataFactory].
         *
         * @param factory the [ElevationDataFactory] to use as an elevation source
         *
         * @return the new elevation source
         */
        @JvmStatic
        fun fromElevationDataFactory(factory: ElevationDataFactory) = ElevationSource(factory)

        /**
         * Constructs an elevation source with a [File].
         *
         * @param file the source [File] to use as an elevation source
         *
         * @return the new elevation source
         */
        @JvmStatic
        fun fromFile(file: File): ElevationSource {
            require(file.isFile) {
                logMessage(ERROR, "ElevationSource", "fromFile", "invalidFile")
            }
            return ElevationSource(file)
        }

        /**
         * Constructs an elevation source with a file path.
         *
         * @param filePath complete path name to the file
         *
         * @return the new elevation source
         */
        @JvmStatic
        fun fromFilePath(filePath: String) = fromFile(File(filePath))

        /**
         * Constructs an elevation source with an [URL].
         *
         * @param url Uniform Resource Locator
         *
         * @return the new elevation source
         */
        @JvmStatic
        fun fromUrl(url: URL) = ElevationSource(url)

        /**
         * Constructs an elevation source with a URL string.
         *
         * @param urlString complete URL string
         *
         * @return the new elevation source
         */
        @JvmStatic
        actual fun fromUrlString(urlString: String) = try {
            fromUrl(URL(urlString))
        } catch (e: MalformedURLException) {
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
        @JvmStatic
        actual fun fromUnrecognized(source: Any) = when(source) {
            is ElevationDataFactory -> fromElevationDataFactory(source)
            is File -> fromFile(source)
            is URL -> fromUrl(source)
            else -> ElevationSource(source)
        }
    }

    /**
     * Buffer post-processing routine.
     */
    @Suppress("UNCHECKED_CAST")
    val bufferPostprocessor get() = postprocessor as ResourcePostprocessor<Buffer>?
    /**
     * Indicates whether this elevation source is an elevation factory.
     */
    val isElevationDataFactory get() = source is ElevationDataFactory
    /**
     * Indicates whether this elevation source is a [File].
     */
    val isFile get() = source is File
    /**
     * Indicates whether this elevation source is an [URL].
     */
    val isUrl get() = source is URL

    /**
     * @return the source [ElevationDataFactory]. Call isElevationFactory to determine whether the source is an elevation
     * factory.
     */
    fun asElevationDataFactory() = source as ElevationDataFactory

    /**
     * @return the source [File]. Call [isFile] to determine whether the source is a [File].
     */
    fun asFile() = source as File

    /**
     * @return the source [URL]. Call [isUrl] to determine whether the source is an [URL].
     */
    fun asUrl() = source as URL

    override fun toString() = when (source) {
        is ElevationDataFactory -> "Factory: $source"
        is File -> "File: $source"
        is URL -> "URL: $source"
        else -> super.toString()
    }

    /**
     * Factory for delegating construction of elevation data.
     */
    interface ElevationDataFactory {
        /**
         * @return the elevation data associated with this factory
         */
        suspend fun fetchElevationData(): Buffer?
    }
}