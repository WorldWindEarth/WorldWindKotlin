package earth.worldwind.layer.ogc3d

import earth.worldwind.layer.cache.CachePolicy
import earth.worldwind.layer.ogc3d.auth.NoAuthProvider
import earth.worldwind.layer.ogc3d.auth.Ogc3dAuthCredentials
import earth.worldwind.layer.ogc3d.auth.TilesetAuthProvider
import earth.worldwind.util.ContentManager

/**
 * Reopen an [Ogc3dTilesLayer] from a [ContentManager] entry registered by an earlier
 * `openBlobStore(...)` + `registerWebService(...)` pair. Reads the persisted
 * [earth.worldwind.layer.cache.WebServiceInfo] (root URI in `.address`, auth-provider
 * class name in `.layerName`, credentials JSON in `.metadata`) and rebuilds the layer
 * end-to-end so the caller only has to do `engine.layers.addLayer(layer)`.
 *
 * Returns null when no entry under [contentKey] exists, when the entry isn't a 3D-Tiles
 * entry (wrong `data_type`), or when the persisted `address` is blank.
 *
 * [authOverride] forces a specific auth provider, ignoring the persisted credentials.
 * Use it when the cache stored credentials that have rotated (a refreshed Ion token,
 * a new Google API key) or when the caller declined to persist them in the first place.
 *
 * [evictionPolicy] is applied to the reopened blob store. The default keeps every cached
 * tile; tighten it when the cache should self-trim after a known-bytes budget.
 */
suspend fun ContentManager.openOgc3dTilesLayer(
    contentKey: String,
    authOverride: TilesetAuthProvider? = null,
    evictionPolicy: CachePolicy = CachePolicy.UNBOUNDED,
): Ogc3dTilesLayer? {
    val entry = findEntry(contentKey) ?: return null
    val info = entry.service ?: return null
    val rootUri = info.address.ifBlank { return null }
    val authProvider = authOverride ?: info.metadata?.let {
        Ogc3dAuthCredentials.decode(info.layerName, it)
    } ?: NoAuthProvider
    val blobStore = openBlobStore(contentKey, evictionPolicy)
    return Ogc3dTilesLayer(
        source = TilesetSource(
            rootUri = rootUri,
            displayName = entry.displayName,
            authProvider = authProvider,
        ),
        displayName = entry.displayName,
        blobStore = blobStore,
    )
}
