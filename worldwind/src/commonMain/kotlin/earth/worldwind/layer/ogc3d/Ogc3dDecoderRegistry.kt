package earth.worldwind.layer.ogc3d

import earth.worldwind.layer.ogc3d.style.TilesetStyleEvaluator
import kotlin.concurrent.Volatile

/**
 * Process-level registry of 3D-Tiles-specific pluggable extensions. Apps wire concrete
 * implementations once at startup; the engine reads the registry from the streaming +
 * render hot path without per-layer plumbing.
 *
 * Codec-style glTF extensions (Draco, KTX2, etc.) live in [earth.worldwind.formats.gltf.GltfDecoderRegistry]
 * because they're general glTF concerns. This registry is for things that only make sense
 * in the 3D Tiles pipeline.
 */
object Ogc3dDecoderRegistry {

    /**
     * Evaluator for the 3D Tiles 1.0 declarative styling language. Null = unsupported;
     * any [earth.worldwind.layer.ogc3d.Ogc3dTilesLayer.style] set on a layer is parsed +
     * stored but its expressions never fire, so features render with the material's
     * base colour and full visibility.
     *
     * Set this once at app startup:
     * ```
     * Ogc3dDecoderRegistry.tilesetStyleEvaluator = MyStyleEvaluator()
     * ```
     */
    @Volatile var tilesetStyleEvaluator: TilesetStyleEvaluator? = null

    /**
     * Decoder for the `3DTILES_draco_point_compression` PNTS extension. Null = unsupported;
     * Draco-compressed `.pnts` tiles log once + skip.
     *
     * Typical wiring (via the worldwind-formats-gltf-draco module, which shares its native
     * bridge across the glTF mesh + PNTS point cloud paths):
     * ```
     * scope.launch { installDracoDecoder() }
     * ```
     */
    @Volatile var dracoPointCloudDecoder: DracoPointCloudDecoder? = null
}
