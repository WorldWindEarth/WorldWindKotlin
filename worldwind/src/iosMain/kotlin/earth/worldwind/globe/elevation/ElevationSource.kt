package earth.worldwind.globe.elevation

import earth.worldwind.util.AbstractSource
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import com.eygraber.uri.Uri

actual open class ElevationSource protected constructor(source: Any) : AbstractSource(source) {
    actual companion object {
        actual fun fromUrlString(urlString: String) = try {
            Uri.parse(urlString) // validate
            ElevationSource(urlString)
        } catch (e: Exception) {
            logMessage(ERROR, "ElevationSource", "fromUrlString", "invalidUrlString", e)
            throw e
        }

        actual fun fromUnrecognized(source: Any) = when (source) {
            is String -> fromUrlString(source)
            else -> ElevationSource(source)
        }
    }

    val isUrl get() = source is String
    fun asUrl() = source as String

    override fun toString() = when (source) {
        is String -> "URL: $source"
        else -> super.toString()
    }
}
