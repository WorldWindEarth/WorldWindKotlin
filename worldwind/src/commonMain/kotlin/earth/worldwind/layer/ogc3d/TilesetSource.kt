package earth.worldwind.layer.ogc3d

import earth.worldwind.layer.ogc3d.auth.NoAuthProvider
import earth.worldwind.layer.ogc3d.auth.TilesetAuthProvider

/**
 * Identifies a 3D Tiles dataset: the absolute URI of its root document (`tileset.json` for a
 * self-hosted set; an asset-endpoint URL for Cesium Ion; `root.json?key=...` for Google
 * Photorealistic 3D Tiles) and the [TilesetAuthProvider] used to authenticate every fetch
 * the layer makes on its behalf.
 */
data class TilesetSource(
    /** Absolute URL of the root document. Relative content URIs inside the dataset are resolved against this. */
    val rootUri: String,
    /** Auth strategy. Default is unauthenticated. */
    val authProvider: TilesetAuthProvider = NoAuthProvider,
    /** Display name used for logging + debug overlays. Defaults to [rootUri]. */
    val displayName: String = rootUri,
)
