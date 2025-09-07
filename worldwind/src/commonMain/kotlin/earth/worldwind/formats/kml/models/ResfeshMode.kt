package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable

/**
 * Specifies a time-based refresh mode
 */
@Serializable
enum class ResfeshMode {
    /**
     * refresh when the file is loaded and whenever the Link parameters change.
     */
    onChange,

    /**
     * refresh every n seconds (specified in <refreshInterval>).
     */
    onInterval,

    /**
     * refresh the file when the expiration time is reached. If a fetched file has a NetworkLinkControl,
     * the <expires> time takes precedence over expiration times specified in HTTP headers.
     * If no <expires> time is specified, the HTTP max-age header is used (if present).
     * If max-age is not present, the Expires HTTP header is used (if present).
     * (See Section RFC261b of the Hypertext Transfer Protocol - HTTP 1.1 for details on HTTP header fields.)
     */
    onExpire;
}