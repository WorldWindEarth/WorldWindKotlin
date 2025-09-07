package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable

/**
 * Specifies how the link is refreshed when the "camera" changes.
 */
@Serializable
enum class ViewRefreshMode {
    /**
     * Ignore changes in the view. Also ignore <viewFormat> parameters, if any.
     */
    never,

    /**
     * Refresh the file n seconds after movement stops, where n is specified in [viewRefreshTime].
     */
    onStop,

    /**
     * Refresh the file only when the user explicitly requests it.
     * (For example, in Google Earth, the user right-clicks and selects Refresh in the Context menu.)
     */
    onRequest,

    /**
     * Refresh the file when the Region becomes active. See [Region].
     */
    onRegion;
}