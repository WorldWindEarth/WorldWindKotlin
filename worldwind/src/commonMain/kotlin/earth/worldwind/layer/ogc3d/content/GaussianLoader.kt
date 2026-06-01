package earth.worldwind.layer.ogc3d.content

/**
 * Pluggable codec for Gaussian-splat 3D Tiles content. The engine has no built-in
 * Gaussian-splat parser today — apps wire one in by setting
 * [earth.worldwind.layer.ogc3d.Ogc3dTilesLayer.gaussianLoader].
 *
 * Why an external hook: there is no single settled wire format yet. Niantic's SPZ has
 * one header, Cesium's draft EXT_3DTILES_gaussian_splat embeds in glTF, SuperSplat's
 * `.splat` files have no magic at all. Each codec implementation lives in its own module
 * and registers itself per layer; the engine stays codec-agnostic.
 *
 * Implementations are stateless and shareable across layers.
 *
 * @see GaussianPayload
 * @see GaussianContent
 */
interface GaussianLoader {
    /**
     * Cheap magic-bytes / shape check. Returns true when this loader can parse [bytes].
     * Must not allocate or throw — called once per fetched payload before the engine
     * decides which loader pipeline owns the content.
     */
    fun supports(bytes: ByteArray): Boolean

    /**
     * Parse [bytes] into a [GaussianPayload]. Called only after [supports] returns true.
     * May throw to signal a malformed payload — the layer treats throws as a tile load
     * failure (the tile transitions to FAILED and stays without content).
     */
    fun parse(bytes: ByteArray): GaussianPayload
}
