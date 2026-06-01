package earth.worldwind.layer.ogc3d.traverse

import earth.worldwind.layer.ogc3d.content.MeshContent
import earth.worldwind.layer.ogc3d.tileset.Refinement
import earth.worldwind.layer.ogc3d.tileset.Tile3d
import earth.worldwind.layer.ogc3d.tileset.Tileset
import earth.worldwind.render.RenderContext

/**
 * Per-frame tile-tree walk. Bottom-up `refines` flag finds the boundary tile where
 * refinement stops; that's what selectTiles renders. Iterative stack — heap-cheap.
 */
class Traverser(
    /** Target pixel error. Lower = sharper, more fetches. */
    var maxScreenSpaceError: Double = 16.0,
    /** Skip tiles farther than this from the camera. 0 disables. */
    var maxDrawDistance: Double = 0.0,
    /** AND-mode (false, default) waits for every sibling; OR-mode refines on first child. */
    var skipLevelOfDetail: Boolean = false,
) {
    class Result {
        val selectedTiles: MutableList<Tile3d> = ArrayList(64)
        val requestedTiles: MutableList<Tile3d> = ArrayList(128)

        internal fun reset() {
            selectedTiles.clear()
            requestedTiles.clear()
        }
    }

    private val result = Result()
    private val traverseStack = ArrayDeque<TraversalEntry>()
    private val emptyTraverseStack = ArrayDeque<Tile3d>()
    private val sortDistances = HashMap<Tile3d, Double>(256)
    private val distanceFirstComparator = Comparator<Tile3d> { a, b ->
        val d = sortDistances.getValue(a).compareTo(sortDistances.getValue(b))
        if (d != 0) d else b.geometricError.compareTo(a.geometricError)
    }

    private class TraversalEntry(val tile: Tile3d, val parent: Tile3d?)

    /** Reused-list result; consume before the next call. */
    fun traverse(rc: RenderContext, tileset: Tileset): Result {
        result.reset()
        // try/finally so a thrown selectTiles (NaN-poisoned bounding sphere etc.) doesn't
        // leave stale TraversalEntries to poison the next frame.
        try {
            selectTiles(rc, tileset.root)
        } finally {
            traverseStack.clear()
            emptyTraverseStack.clear()
        }
        // Fetch closest + shallowest first. Cache distances up front so the comparator
        // does HashMap lookups instead of recomputing sqrt N log N times — the sort
        // comparator showed up at ~1.9% of main-thread time in simpleperf, plus 1.2%
        // Vec3.distanceTo, almost entirely from this sort.
        sortDistances.clear()
        for (tile in result.requestedTiles) sortDistances[tile] = cameraDistance(rc, tile)
        result.requestedTiles.sortWith(distanceFirstComparator)
        return result
    }

    private fun selectTiles(rc: RenderContext, root: Tile3d) {
        traverseStack.addLast(TraversalEntry(root, null))
        while (traverseStack.isNotEmpty()) {
            val entry = traverseStack.removeLast()
            val tile = entry.tile
            if (!intersectsFrustum(rc, tile)) {
                tile.refines = false
                continue
            }
            val distance = cameraDistance(rc, tile)
            if (maxDrawDistance > 0.0 && distance > maxDrawDistance) {
                tile.refines = false
                continue
            }

            val parentRefine = entry.parent?.refines ?: true
            // When the parent has nothing to draw (wrapper without renderable content, or
            // mesh-content tile not loaded yet), letting its `refines=false` cascade would
            // suppress every child in this cohort and leave the area blank — that's the
            // "Manhattan disappears on rotation" symptom, because Google Photoreal puts
            // multiple wrapper layers between renderable tiles, and one mid-fetch lateral
            // sibling drags the whole cohort to a non-renderable ancestor. Treat the
            // non-renderable-parent case as "refine through" so loaded children still draw
            // at their own LOD; unloaded ones produce gaps that pop in as fetches complete.
            val parentCanFallback = entry.parent?.let { isRenderableContent(it) } ?: false
            val effectiveParentRefine = parentRefine || !parentCanFallback
            tile.refines = if (canTraverse(rc, tile, distance)) {
                traverseChildren(rc, tile) && effectiveParentRefine
            } else {
                false
            }
            val stoppedRefining = !tile.refines && effectiveParentRefine
            val tileUri = tile.contentUri
            val hasRenderable = tileUri != null && !tileUri.endsWith(".json", ignoreCase = true)
            val isEmptyLeaf = tileUri == null && tile.children.isEmpty()

            if (isEmptyLeaf) continue

            when (tile.refinement) {
                Refinement.ADD -> {
                    // ADD: parent always renders + children layer on top.
                    if (hasRenderable) {
                        loadTile(rc, tile, distance)
                        if (tile.content != null) result.selectedTiles.add(tile)
                    } else {
                        loadTile(rc, tile, distance)
                    }
                }
                Refinement.REPLACE -> {
                    // REPLACE: render at the refinement boundary. When the cohort is partially
                    // loaded, this fallback draws on top of any loaded fine descendants — a
                    // known visible overlap until the draw path applies a polygon offset to
                    // push the coarse fallback behind the fine geometry. Without it the
                    // not-yet-loaded sibling areas would render as black holes, which the user
                    // ranks as worse than the overlap.
                    loadTile(rc, tile, distance)
                    if (stoppedRefining && hasRenderable && tile.content != null) {
                        result.selectedTiles.add(tile)
                    }
                }
            }
        }
    }

    /** Traversable iff there are children and either it's a tileset-content node (no SSE
     *  budget of its own) or the SSE check exceeds [maxScreenSpaceError]. */
    private fun canTraverse(rc: RenderContext, tile: Tile3d, distance: Double): Boolean {
        if (tile.children.isEmpty()) return false
        val uri = tile.contentUri
        if (uri == null || uri.endsWith(".json", ignoreCase = true)) return true
        val sse = ScreenSpaceError.compute(rc, tile.geometricError, distance)
        return sse > maxScreenSpaceError
    }

    /** Push visible children + aggregate `refines` across the cohort. AND or OR per
     *  [skipLevelOfDetail]; AND mode also pre-fetches off-screen siblings. */
    private fun traverseChildren(rc: RenderContext, tile: Tile3d): Boolean {
        val checkRefines = tile.refinement == Refinement.REPLACE && tile.contentUri != null
        var refines = !skipLevelOfDetail
        var anyChildVisible = false

        for (child in tile.children) {
            val visible = intersectsFrustum(rc, child)
            if (visible) {
                traverseStack.addLast(TraversalEntry(child, tile))
                anyChildVisible = true
            } else if (checkRefines && !skipLevelOfDetail) {
                // Pre-fetch direct off-frustum siblings only (no recursion into their
                // subtrees) so a small pan finds them warm. Bounded by the fetch-queue
                // semaphore end-to-end, not by tree depth.
                loadTile(rc, child, cameraDistance(rc, child))
            }
            // Refinement is gated by visible children only — off-frustum siblings get
            // pre-fetched but don't AND into the parent's refinement decision.
            if (visible && checkRefines) {
                val childRefines = if (child.contentUri == null) {
                    traverseEmpty(rc, child)
                } else {
                    isResourcesLoaded(rc, child)
                }
                refines = if (skipLevelOfDetail) refines || childRefines
                          else                    refines && childRefines
            }
        }

        return anyChildVisible && refines
    }

    /** Empty-subtree "refines": AND of every visible renderable descendant. Off-frustum
     *  descendants are skipped (caller's level pre-fetches direct off-frustum siblings). */
    private fun traverseEmpty(rc: RenderContext, root: Tile3d): Boolean {
        var allLoaded = true
        emptyTraverseStack.addLast(root)
        while (emptyTraverseStack.isNotEmpty()) {
            val tile = emptyTraverseStack.removeLast()
            if (!intersectsFrustum(rc, tile)) continue
            val distance = cameraDistance(rc, tile)
            val isEmpty = tile.contentUri == null
            val canDescend = isEmpty && canTraverse(rc, tile, distance)
            if (!canDescend && !isResourcesLoaded(rc, tile)) allLoaded = false
            if (canDescend) for (child in tile.children) emptyTraverseStack.addLast(child)
        }
        return allLoaded
    }

    /** Enqueue [tile] for the layer to fetch / touch this frame. External tileset refs
     *  (content URI ending in `.json`) are gated by SSE: at street-altitude views of a
     *  deeply-nested tileset like Google Photoreal the traverser would otherwise fetch +
     *  parse + URI-resolve dozens of lateral .json subtrees we'd never refine into. The
     *  per-tileset parse cost is the dominant remaining sink in the parse-pool profile
     *  (~330% inclusive on the coroutine pool after the URI fast path). Wrappers (no URI)
     *  and renderable mesh content always go through — wrappers are free, meshes are
     *  load-on-demand at the layer's existing concurrency cap. */
    private fun loadTile(rc: RenderContext, tile: Tile3d, distance: Double) {
        val uri = tile.contentUri
        if (uri != null && uri.endsWith(".json", ignoreCase = true)) {
            val sse = ScreenSpaceError.compute(rc, tile.geometricError, distance)
            if (sse <= maxScreenSpaceError) return
        }
        result.requestedTiles.add(tile)
    }

    private fun isResourcesLoaded(rc: RenderContext, tile: Tile3d): Boolean {
        if (tile.contentUri == null) return true
        val content = tile.content ?: return false
        return when (content) {
            is MeshContent -> content.isResourcesLoaded(rc)
            // Point-cloud / Gaussian self-upload every frame via their syncContentGpu paths.
            else -> true
        }
    }

    /** True when [tile] has loaded, drawable mesh/points content (not a wrapper or external
     *  tileset reference). Used as the "can serve as fallback render" predicate. */
    private fun isRenderableContent(tile: Tile3d): Boolean {
        val uri = tile.contentUri
        return uri != null && !uri.endsWith(".json", ignoreCase = true) && tile.content != null
    }

    private fun intersectsFrustum(rc: RenderContext, tile: Tile3d): Boolean =
        tile.worldBoundingSphere(rc.globe).intersectsFrustum(rc.frustum)

    private fun cameraDistance(rc: RenderContext, tile: Tile3d): Double {
        val sphere = tile.worldBoundingSphere(rc.globe)
        val centerDist = rc.cameraPoint.distanceTo(sphere.center)
        return (centerDist - sphere.radius).coerceAtLeast(0.0)
    }
}
