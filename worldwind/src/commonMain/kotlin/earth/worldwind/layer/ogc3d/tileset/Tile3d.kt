package earth.worldwind.layer.ogc3d.tileset

import earth.worldwind.geom.BoundingSphere
import earth.worldwind.geom.Matrix4
import earth.worldwind.globe.Globe
import earth.worldwind.layer.ogc3d.content.TileContent
import kotlin.concurrent.Volatile

/** Runtime tree node decoded from `tileset.json`. Mutated only by traversal + streaming. */
class Tile3d internal constructor(
    val boundingVolume: BoundingVolume,
    /** Spec-defined geometric error in meters. */
    val geometricError: Double,
    val refinement: Refinement,
    /** This tile's tile-to-world composed with all ancestors'. */
    val tileToWorld: Matrix4,
    /** Null when this tile is structural (no renderable payload). Cleared when the URI
     *  pointed at an external tileset.json and its root has been grafted into [children]. */
    @Volatile
    var contentUri: String?,
    /** Mutated by external-tileset grafts on the fetch coroutine; read by the render thread. */
    @Volatile
    var children: List<Tile3d>,
    /** When non-null, traversal materialises children via [SubtreeReader]. */
    val implicitTiling: ImplicitTilingDescription? = null,
) {
    private val cachedWorldSphere = BoundingSphere()
    private var cachedWorldSphereValid = false

    fun worldBoundingSphere(globe: Globe): BoundingSphere {
        if (!cachedWorldSphereValid) {
            boundingVolume.worldBoundingSphere(globe, tileToWorld, cachedWorldSphere)
            cachedWorldSphereValid = true
        }
        return cachedWorldSphere
    }

    /** Force [worldBoundingSphere] to recompute on next access. Called by the layer when
     *  it rebakes [tileToWorld] (e.g. altitude offset change). */
    internal fun invalidateWorldBoundingSphere() {
        cachedWorldSphereValid = false
    }

    @Volatile var content: TileContent? = null

    @Volatile var loadState: LoadState = LoadState.UNLOADED

    /** Bottom-up "children take over rendering" flag computed by the traverser. */
    var refines: Boolean = false

    /** Set by the traverser when finer descendants are also in selection (skip-LoD).
     *  Drives stencil masking in the draw path so fallback tiles fill only gaps. */
    var isFallback: Boolean = false

    /** Outermost-fallback subtree id (0 = no fallback ancestor → stencil bypassed;
     *  1..255 = inside a fallback subtree). Non-fallback tiles write; fallback tiles
     *  draw where stencil != this id. */
    var stencilId: Int = 0

    /** Set by the traverser when the tile was accepted only as a shadow caster (outside the
     *  view frustum). The layer enqueues it occluder-only: cascade depth pass, no color pass. */
    var isShadowOnly: Boolean = false

    /** Scratch sort key set by [Traverser] each frame for distance-first ordering.
     *  Direct primitive field — avoids the boxed `HashMap<Tile3d, Double>` lookup that
     *  showed up as `Double.valueOf` 271 samples on the main thread at globe scale. */
    var traversalDistance: Double = 0.0

    /** Last frame this tile participated in traversal — read by the layer's cold-subtree sweep. */
    @Volatile var lastSelectedFrame: Long = 0L

    /** Original `.json` URI when [children] were grafted from an external tileset; null otherwise.
     *  Preserved so the sweep can restore the wrapper for re-fetch when the subtree is evicted. */
    @Volatile var graftedFromUri: String? = null

    enum class LoadState {
        UNLOADED,
        FETCHING,
        PARSING,
        LOADED,
        FAILED,
    }
}
