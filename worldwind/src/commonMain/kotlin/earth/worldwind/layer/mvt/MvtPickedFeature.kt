package earth.worldwind.layer.mvt

/**
 * Payload exposed via [earth.worldwind.PickedObject.userObject] when a vector-tile feature is
 * picked. Carries the original MVT layer name, geometry type, and (lazily) the inflated property
 * map so an app can `pickedObject.userObject as? MvtPickedFeature` and read e.g. the feature's
 * `name` or `kind` to drive an info panel.
 *
 * To avoid retaining a built map per feature (only the actually-picked one is ever read), it holds the raw
 * [tags] + the layer's shared key/value dictionaries and inflates [properties] lazily on first access.
 *
 * Created at tile fetch time when [MvtVectorLayer.isPickEnabled] is true and the layer is
 * using the batched render path; null elsewhere.
 */
class MvtPickedFeature(
    /** Source layer name from the MVT (`"streets"`, `"water_polygons"`, `"buildings"`, …). */
    val layerName: String,
    val geometryType: MvtGeometryType,
    /** Slippy-tile coordinates of the source tile (z, x, y), for users that need provenance. */
    val tile: MvtVectorLayer.TileKey,
    // Raw inputs kept instead of a built map: [keys]/[values] are the layer's shared dictionaries (one ref each), [tags] this feature's index-pair list.
    private val tags: IntArray,
    private val keys: List<String>,
    private val values: List<Any?>,
) {
    // Manual (non-synchronized) lazy cache instead of `by lazy` — its SynchronizedLazyImpl + lambda per instance is real heap weight at hundreds of thousands of features.
    private var cachedProperties: Map<String, Any?>? = null

    /** Feature properties, inflated against the layer dictionaries on first access (deferring the build off the per-tile assembly path to the actual pick). */
    val properties: Map<String, Any?>
        get() = cachedProperties ?: inflateMvtProperties(tags, keys, values).also { cachedProperties = it }

    // Identity = (layer, geometry, tile, tag content): same as the old data class's built-map identity, but read from raw tags so feature-state keying needs no inflation.
    override fun equals(other: Any?): Boolean = this === other ||
        (other is MvtPickedFeature && layerName == other.layerName && tile == other.tile &&
            geometryType == other.geometryType && tags.contentEquals(other.tags))

    override fun hashCode(): Int {
        var h = layerName.hashCode()
        h = 31 * h + geometryType.hashCode()
        h = 31 * h + tile.hashCode()
        h = 31 * h + tags.contentHashCode()
        return h
    }

    override fun toString(): String = "MvtPickedFeature($layerName, $geometryType, $tile)"
}
