package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.layer.cache.BlobStore
import earth.worldwind.layer.cache.WebServiceInfo
import earth.worldwind.layer.ogc3d.Ogc3dTilesLayer
import earth.worldwind.layer.ogc3d.auth.CesiumIonAuthProvider
import earth.worldwind.layer.ogc3d.auth.TilesetAuthProvider
import earth.worldwind.layer.shadow.ShadowMode

/**
 * Cesium Ion 3D Tiles — streams a hosted Cesium Ion asset.
 *
 * Setup:
 *  1. Sign in to Cesium Ion: https://ion.cesium.com/
 *  2. Generate an access token: https://ion.cesium.com/tokens
 *  3. Paste it into [cesiumIonAccessToken] below.
 *
 * Defaults to **Cesium OSM Buildings** (asset id 96188) — a global, free Ion asset of
 * OpenStreetMap building footprints. Override [cesiumIonAssetId] and [cameraLookAt] to swap to
 * any other Ion asset; subclasses can override [installCodecs] to register Draco / KTX2
 * decoders for assets that need them (e.g. photogrammetry).
 *
 * The provider points the layer at `assets/<id>/endpoint`. The first response is the per-asset
 * endpoint envelope, which yields the real tileset URL plus a short-lived access token;
 * [CesiumIonAuthProvider] captures that token and reuses it on every subsequent fetch.
 */
open class CesiumIon3dTilesTutorial(
    engine: WorldWind,
    cacheProvider: (suspend (info: WebServiceInfo) -> BlobStore)? = null,
) : Ogc3dTilesTutorial(engine, cacheProvider) {

    // Injected via CESIUM_ION_TOKEN env var (see generateTutorialApiKeys); placeholder otherwise.
    protected open val cesiumIonAccessToken: String =
        TutorialApiKeys.CESIUM_ION.ifBlank { "<YOUR-CESIUM-ION-ACCESS-TOKEN-HERE>" }

    /** Cesium Ion asset id. Default is the Melbourne point-cloud sample asset. */
    protected open val cesiumIonAssetId: Long = 43978

    override val tilesetUri: String
        get() = "https://api.cesium.com/v1/assets/$cesiumIonAssetId/endpoint"

    override val layerDisplayName: String = "Cesium Ion 3D Tiles"
    override val authProvider: TilesetAuthProvider = CesiumIonAuthProvider(cesiumIonAccessToken)

    /** Melbourne CBD. */
    override val cameraLookAt: LookAt = LookAt(
        position = Position((-37.8136).degrees, 144.9631.degrees, 30.0),
        altitudeMode = AltitudeMode.ABSOLUTE,
        range = 2000.0,
        heading = 0.0.degrees,
        tilt = 65.0.degrees,
        roll = 0.0.degrees,
    )

    override fun configureLayer(layer: Ogc3dTilesLayer) {
        layer.shadowMode = ShadowMode.DISABLED
    }
}
