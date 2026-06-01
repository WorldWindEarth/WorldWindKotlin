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

    enum class LoadState {
        UNLOADED,
        FETCHING,
        PARSING,
        LOADED,
        FAILED,
    }
}
