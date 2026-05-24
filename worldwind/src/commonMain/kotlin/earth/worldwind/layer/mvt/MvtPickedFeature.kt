package earth.worldwind.layer.mvt

/**
 * Payload exposed via [earth.worldwind.PickedObject.userObject] when a vector-tile feature is
 * picked. Carries the original MVT layer name, geometry type, and inflated property map so
 * an app can `pickedObject.userObject as? MvtPickedFeature` and read e.g. the feature's
 * `name` or `kind` to drive an info panel.
 *
 * Created at tile fetch time when [MvtVectorLayer.isPickEnabled] is true and the layer is
 * using the batched render path; null elsewhere.
 */
data class MvtPickedFeature(
    /** Source layer name from the MVT (`"streets"`, `"water_polygons"`, `"buildings"`, …). */
    val layerName: String,
    val geometryType: MvtGeometryType,
    /** Feature properties inflated against the layer's key/value dictionaries. */
    val properties: Map<String, Any?>,
    /** Slippy-tile coordinates of the source tile (z, x, y), for users that need provenance. */
    val tile: MvtVectorLayer.TileKey,
)
