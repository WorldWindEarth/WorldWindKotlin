package earth.worldwind.layer.ogc3d.tileset

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 3D Tiles 1.1 implicit-tiling metadata. Replaces explicit per-tile JSON declarations
 * with a coordinate-derivable tree: every (level, x, y[, z]) within `availableLevels`
 * potentially has a tile, and on-demand `.subtree` availability bitstreams say which
 * coordinates are actually populated.
 *
 * Spec: <https://github.com/CesiumGS/3d-tiles/tree/main/specification/ImplicitTiling>.
 */
@Serializable
class ImplicitTilingDescription(
    /** `QUADTREE` (4-way XY subdivision) or `OCTREE` (8-way XYZ). */
    val subdivisionScheme: SubdivisionScheme = SubdivisionScheme.QUADTREE,
    /** Levels per subtree file. Subtree at (level, x, y) covers `subtreeLevels` levels
     *  below it; children at level + subtreeLevels live in a separate subtree file. */
    val subtreeLevels: Int = 1,
    /** Total levels in the tree, inclusive of the root. Implementations may stop refining
     *  at `availableLevels - 1` even when the user requests deeper LOD. */
    val availableLevels: Int = 1,
    /** Template for resolving per-(level, x, y[, z]) subtree availability files. The URI
     *  contains `{level}` / `{x}` / `{y}` / optional `{z}` placeholders the layer
     *  substitutes when fetching a subtree. */
    val subtrees: ImplicitUriTemplate = ImplicitUriTemplate(),
)

@Serializable
enum class SubdivisionScheme {
    @SerialName("QUADTREE") QUADTREE,
    @SerialName("OCTREE") OCTREE,
}

@Serializable
class ImplicitUriTemplate(
    val uri: String = "",
)

/**
 * One decoded `.subtree` file. Carries the availability bitstreams the layer's traversal
 * code consults when expanding implicit children.
 *
 * Spec subtree file layout (header is 24 bytes little-endian):
 *  - bytes 0..3:    magic `"subt"`
 *  - bytes 4..7:    version (uint32, currently 1)
 *  - bytes 8..15:   JSON chunk byte length (uint64)
 *  - bytes 16..23:  binary chunk byte length (uint64)
 *  - bytes 24..:    JSON chunk
 *  - bytes 24+jsonLen..: binary chunk
 *
 * The JSON describes which bytes of the binary chunk hold which availability bitstream;
 * see [SubtreeReader.parse] for the actual bit-extraction logic.
 */
class Subtree internal constructor(
    /** Bit `i` = 1 iff the i-th tile in level-order traversal of this subtree exists.
     *  Always non-null; constant `1` when the subtree's metadata says all tiles exist. */
    val tileAvailability: BooleanArray,
    /** Per-content-slot availability. Most implicit tilesets have a single content slot;
     *  some publish multiple (mesh + point cloud, etc.) where each slot has its own bit
     *  pattern. Empty list = no content available in this subtree. */
    val contentAvailability: List<BooleanArray>,
    /** Bit `i` = 1 iff the i-th leaf of this subtree has a child subtree at the next
     *  level boundary. Used to know which `{level}/{x}/{y}.subtree` URIs to fetch
     *  recursively. Empty when no children exist. */
    val childSubtreeAvailability: BooleanArray,
)
