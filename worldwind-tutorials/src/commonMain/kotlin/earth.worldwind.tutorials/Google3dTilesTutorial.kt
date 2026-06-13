package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.layer.cache.BlobStore
import earth.worldwind.layer.cache.WebServiceInfo
import earth.worldwind.layer.ogc3d.auth.GoogleTilesAuthProvider
import earth.worldwind.layer.ogc3d.auth.TilesetAuthProvider

/**
 * Google Photorealistic 3D Tiles — globe-scale photoreal mesh streamed from the Map Tiles API.
 *
 * Setup:
 *  1. Enable the Map Tiles API:
 *     https://console.cloud.google.com/apis/library/tile.googleapis.com
 *  2. Create an API key:
 *     https://console.cloud.google.com/apis/credentials
 *  3. Paste it into [googleApiKey] below.
 *
 * The [GoogleTilesAuthProvider] propagates the `session=` token from the root response down
 * to every child fetch automatically; the layer auto-enables [Ogc3dTilesLayer.googlePhotorealistic3dTilesMode]
 * the moment it sees that auth provider (Google ships node matrices in row-vector form and
 * content already in tile-local Z-up — both quirks are handled there).
 */
open class Google3dTilesTutorial(
    engine: WorldWind,
    cacheProvider: (suspend (info: WebServiceInfo) -> BlobStore)? = null,
) : Ogc3dTilesTutorial(engine, cacheProvider) {

    // Injected via GOOGLE_MAPS_API_KEY env var (see generateTutorialApiKeys); placeholder otherwise.
    protected open val googleApiKey: String =
        TutorialApiKeys.GOOGLE_MAPS.ifBlank { "<YOUR-GOOGLE-MAPS-API-KEY-HERE>" }
    override val tilesetUri: String = "https://tile.googleapis.com/v1/3dtiles/root.json"
    override val layerDisplayName: String = "Google 3D Tiles"
    override val authProvider: TilesetAuthProvider = GoogleTilesAuthProvider(googleApiKey)

    // Google Photoreal IS the visible ground — strip base imagery + terrain so it isn't
    // depth-occluded by the ellipsoid surface at sub-meter altitudes.
    override val replaceBaseLayers: Boolean = true

    /** Midtown Manhattan, looking south down 5th Avenue. */
    override val cameraLookAt: LookAt = LookAt(
        position = Position(40.7589.degrees, (-73.9851).degrees, 100.0),
        altitudeMode = AltitudeMode.ABSOLUTE,
        range = 1500.0,
        heading = 180.0.degrees,
        tilt = 65.0.degrees,
        roll = 0.0.degrees,
    )

}
