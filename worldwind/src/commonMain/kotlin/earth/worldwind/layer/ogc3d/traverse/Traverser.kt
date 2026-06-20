package earth.worldwind.layer.ogc3d.traverse

import earth.worldwind.layer.ogc3d.content.GaussianContent
import earth.worldwind.layer.ogc3d.content.MeshContent
import earth.worldwind.layer.ogc3d.content.PointCloudContent
import earth.worldwind.layer.ogc3d.content.isReadyForRefinement
import earth.worldwind.layer.ogc3d.tileset.Refinement
import earth.worldwind.layer.ogc3d.tileset.Tile3d
import earth.worldwind.layer.ogc3d.tileset.Tileset
import earth.worldwind.render.RenderContext

/** Per-frame tile-tree walk. Emits selected tiles (drawables) + requested tiles (fetches). */
class Traverser(
    /** Target pixel error. Lower = sharper, more fetches. */
    var maxScreenSpaceError: Double = 16.0,
    /** Skip tiles farther than this from the camera. 0 disables. */
    var maxDrawDistance: Double = 0.0,
    /** OR-mode: refine on first loaded child and also select the parent as a stencil-masked
     *  fallback when any sibling is still loading. AND-mode (`false`) waits for every visible
     *  sibling. OR-mode is the Cesium skip-LoD default. */
    var skipLevelOfDetail: Boolean = true,
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
    /** Parent map built incrementally as selectTiles pushes children; markFallbacks and
     *  assignStencilIds walk UP via it in O(N×depth) instead of DFS'ing the whole tree. */
    private val parentMap = HashMap<Tile3d, Tile3d?>(512)
    private val minDescendantGE = HashMap<Tile3d, Double>(64)
    private val outermostFallbackIdMap = HashMap<Tile3d, Int>(8)
    private val distanceFirstComparator = Comparator<Tile3d> { a, b ->
        val d = sortDistances.getValue(a).compareTo(sortDistances.getValue(b))
        if (d != 0) d else b.geometricError.compareTo(a.geometricError)
    }
    /** Reused membership set for markFallbacks + assignStencilIds. Cleared and refilled
     *  each [traverse]; avoids a per-frame HashSet allocation. */
    private val selectedSet = HashSet<Tile3d>(128)
    /** Cached draw-order comparator: non-fallback ascending by GE then fallback descending.
     *  Building it via `compareBy{…}.thenBy{…}.thenByDescending{…}` each frame creates a
     *  chain of wrapper objects + lambda captures — measurable GC churn at SSE 8. */
    private val selectedSortComparator = Comparator<Tile3d> { a, b ->
        val aFallback = if (a.isFallback) 1 else 0
        val bFallback = if (b.isFallback) 1 else 0
        val byFallback = aFallback.compareTo(bFallback)
        if (byFallback != 0) byFallback
        else if (!a.isFallback) a.geometricError.compareTo(b.geometricError)
        else b.geometricError.compareTo(a.geometricError)
    }

    private class TraversalEntry(val tile: Tile3d, val parent: Tile3d?)

    /** OR-mode side channel: `traverseChildren` writes whether any visible child was not yet
     *  loaded so `selectTiles` can decide if the parent enters selection as a fallback. */
    private var lastCohortHasUnloadedChild: Boolean = false

    /** Reused-list result; consume before the next call. */
    fun traverse(rc: RenderContext, tileset: Tileset): Result {
        result.reset()
        parentMap.clear()
        parentMap[tileset.root] = null
        // try/finally so a thrown selectTiles (NaN-poisoned bounding sphere etc.) doesn't
        // leave stale TraversalEntries to poison the next frame.
        try {
            selectTiles(rc, tileset.root)
        } finally {
            traverseStack.clear()
            emptyTraverseStack.clear()
        }
        // Fetch closest+shallowest first. Cache distances so the comparator does HashMap
        // lookups instead of recomputing sqrt N log N times.
        sortDistances.clear()
        for (tile in result.requestedTiles) sortDistances[tile] = cameraDistance(rc, tile)
        result.requestedTiles.sortWith(distanceFirstComparator)
        selectedSet.clear()
        selectedSet.addAll(result.selectedTiles)
        markFallbacks(selectedSet)
        assignStencilIds(selectedSet)
        result.selectedTiles.sortWith(selectedSortComparator)
        return result
    }

    /** A REPLACE tile is a fallback iff some selected descendant has GE < my_GE /
     *  [FALLBACK_GE_RATIO]. ADD tiles are never fallbacks — parent + child both fully
     *  render, and stencil-masking the parent would clip its geometry under the children. */
    private fun markFallbacks(selectedSet: HashSet<Tile3d>) {
        if (selectedSet.isEmpty()) return
        minDescendantGE.clear()
        for (tile in selectedSet) {
            var cur = parentMap[tile]
            val tileGE = tile.geometricError
            while (cur != null) {
                if (cur in selectedSet) {
                    val existing = minDescendantGE[cur]
                    if (existing == null || tileGE < existing) minDescendantGE[cur] = tileGE
                }
                cur = parentMap[cur]
            }
        }
        for (tile in selectedSet) {
            if (tile.refinement == Refinement.ADD) {
                tile.isFallback = false
                continue
            }
            val minGE = minDescendantGE[tile] ?: Double.POSITIVE_INFINITY
            tile.isFallback = minGE < tile.geometricError / FALLBACK_GE_RATIO
        }
    }

    /** Assign each selected tile a stencil id identifying its outermost-fallback subtree.
     *  Tiles in the same fallback subtree get the same id; tiles with no fallback ancestor
     *  in selection get id 0. Walks `result.selectedTiles` in insertion order so the same
     *  selection produces the same id mapping frame-over-frame.
     *
     *  Out of ids: once [MAX_STENCIL_ID] distinct subtrees have been numbered, remaining
     *  fallback subtrees get id 0 (no masking). They overdraw their finer descendants —
     *  worse fill than masked rendering, but never the cross-subtree corruption that
     *  silent id-wraparound produces. */
    private fun assignStencilIds(selectedSet: HashSet<Tile3d>) {
        outermostFallbackIdMap.clear()
        var nextId = 1
        for (tile in result.selectedTiles) {
            var outermost: Tile3d? = if (tile.isFallback) tile else null
            var cur = parentMap[tile]
            while (cur != null) {
                if (cur in selectedSet && cur.isFallback) outermost = cur
                cur = parentMap[cur]
            }
            if (outermost == null) {
                tile.stencilId = 0
                continue
            }
            val existing = outermostFallbackIdMap[outermost]
            if (existing != null) {
                tile.stencilId = existing
            } else if (nextId <= MAX_STENCIL_ID) {
                outermostFallbackIdMap[outermost] = nextId
                tile.stencilId = nextId
                nextId++
            } else {
                tile.stencilId = 0
            }
        }
    }

    companion object {
        /** Descendants two refinement levels finer (1/4× GE) trigger the fallback flag. */
        private const val FALLBACK_GE_RATIO = 4.0
        /** Skip-LoD ids use stencil bits 0..6 (bit 7 reserved for GROUND_COVERED_BIT). 0 =
         *  no fallback ancestor (fast-path); excess subtrees beyond 127 fall back to 0
         *  (harmless overdraw — no skip-LoD masking). */
        private const val MAX_STENCIL_ID = 127
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
            // mesh-content tile not loaded yet), treat it as "refine through" so loaded
            // children still draw at their own LOD — otherwise one mid-fetch lateral sibling
            // drags the whole cohort to a non-renderable ancestor and the area goes blank.
            val parentCanFallback = entry.parent?.let { isRenderableContent(it) } ?: false
            val effectiveParentRefine = parentRefine || !parentCanFallback
            // Reset before each cohort so an upstream sibling's value doesn't leak through a
            // leaf tile that never enters traverseChildren.
            lastCohortHasUnloadedChild = false
            tile.refines = if (canTraverse(rc, tile, distance)) {
                traverseChildren(rc, tile) && effectiveParentRefine
            } else {
                false
            }
            val cohortHasUnloadedChild = lastCohortHasUnloadedChild
            val stoppedRefining = !tile.refines && effectiveParentRefine
            // Skip-LoD: when refinement continues (some children loaded) but others aren't,
            // this tile renders as a stencil-masked fallback. Without this, OR-mode produces
            // holes where the unloaded children would have drawn.
            val cohortNeedsFallback = tile.refines && cohortHasUnloadedChild && effectiveParentRefine
            // markFallbacks/assignStencilIds re-set isFallback after traversal completes.
            tile.isFallback = false
            val tileUri = tile.contentUri
            val hasRenderable = tileUri != null && !tileUri.endsWith(".json", ignoreCase = true)
            val isEmptyLeaf = tileUri == null && tile.children.isEmpty()

            if (isEmptyLeaf) continue

            when (tile.refinement) {
                Refinement.ADD -> {
                    if (hasRenderable) {
                        loadTile(rc, tile, distance)
                        if (tile.content != null) result.selectedTiles.add(tile)
                    } else {
                        loadTile(rc, tile, distance)
                    }
                }
                Refinement.REPLACE -> {
                    loadTile(rc, tile, distance)
                    if ((stoppedRefining || cohortNeedsFallback) && hasRenderable && tile.content != null) {
                        result.selectedTiles.add(tile)
                    }
                }
            }
        }
    }

    /** Traversable iff there are children and either the node is wrapper/external (no SSE
     *  budget of its own) or the SSE check exceeds [maxScreenSpaceError]. */
    private fun canTraverse(rc: RenderContext, tile: Tile3d, distance: Double): Boolean {
        if (tile.children.isEmpty()) return false
        val uri = tile.contentUri
        if (uri == null || uri.endsWith(".json", ignoreCase = true)) return true
        val sse = ScreenSpaceError.compute(rc, tile.geometricError, distance)
        return sse > maxScreenSpaceError
    }

    /** Push visible children, aggregate `refines` across the cohort (AND/OR per
     *  [skipLevelOfDetail]). AND mode also pre-fetches direct off-frustum siblings so a
     *  small pan finds them warm. Writes [lastCohortHasUnloadedChild]. */
    private fun traverseChildren(rc: RenderContext, tile: Tile3d): Boolean {
        val checkRefines = tile.refinement == Refinement.REPLACE && tile.contentUri != null
        var refines = !skipLevelOfDetail
        var anyChildVisible = false
        var anyChildUnloaded = false

        for (child in tile.children) {
            parentMap[child] = tile
            val visible = intersectsFrustum(rc, child)
            if (visible) {
                traverseStack.addLast(TraversalEntry(child, tile))
                anyChildVisible = true
            } else if (checkRefines && !skipLevelOfDetail) {
                loadTile(rc, child, cameraDistance(rc, child))
            }
            if (visible && checkRefines) {
                val childRefines = if (child.contentUri == null) traverseEmpty(rc, child)
                                   else isResourcesLoaded(rc, child)
                if (!childRefines) anyChildUnloaded = true
                refines = if (skipLevelOfDetail) refines || childRefines
                          else                    refines && childRefines
            }
        }

        lastCohortHasUnloadedChild = anyChildUnloaded
        return anyChildVisible && refines
    }

    /** Empty-subtree refines: AND of every visible renderable descendant. Off-frustum
     *  descendants are skipped (caller pre-fetches direct off-frustum siblings). */
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

    /** Enqueue [tile] for the layer to fetch / touch this frame. External tileset refs are
     *  SSE-gated so we don't fetch deep lateral .json subtrees we'd never refine into. */
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
            is PointCloudContent -> content.isReadyForRefinement(rc)
            is GaussianContent -> content.isReadyForRefinement(rc)
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
