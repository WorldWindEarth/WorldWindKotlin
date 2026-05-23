package earth.worldwind.ogc.wfs

/**
 * Thrown when a WFS endpoint returns an OGC ExceptionReport instead of the requested
 * payload. Carries the server-supplied exception code and text so callers can surface
 * meaningful diagnostics instead of an opaque XML parse failure.
 */
class WfsServiceException(
    val exceptionCode: String?,
    val exceptionText: String?,
    val locator: String? = null,
) : RuntimeException(
    buildString {
        append("WFS server returned an exception")
        exceptionCode?.let { append(" [").append(it).append("]") }
        locator?.let { append(" at ").append(it) }
        exceptionText?.let { append(": ").append(it) }
    }
)
