package earth.worldwind.layer.ogc3d.tileset

/**
 * Top-level decoded form of a `tileset.json` document. Holds the root [Tile3d] plus
 * asset-level metadata the runtime cares about.
 */
class Tileset internal constructor(
    val root: Tile3d,
    val geometricError: Double,
    /** Asset's declared `gltfUpAxis`. Null when omitted — the layer falls back to the 3D
     *  Tiles 1.0 spec default (Y = apply rotation) when both this and the per-tile b3dm
     *  override are absent. */
    val gltfUpAxis: GltfUpAxis?,
    val assetVersion: String,
    val extensionsUsed: List<String>,
)

/** 3D Tiles asset `gltfUpAxis` declaration. */
enum class GltfUpAxis {
    X, Y, Z;

    companion object {
        /** Parse a `gltfUpAxis` string. Returns null when the value is missing or unrecognised. */
        fun fromStringOrNull(value: String?): GltfUpAxis? = when (value?.uppercase()) {
            "X" -> X
            "Y" -> Y
            "Z" -> Z
            else -> null
        }
    }
}
