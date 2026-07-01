package earth.worldwind.layer.ogc3d.tileset

import earth.worldwind.formats.i3s.I3sSceneLayer
import earth.worldwind.formats.i3s.NodeDoc
import earth.worldwind.formats.i3s.NodePageDoc
import earth.worldwind.formats.i3s.SceneLayerDoc
import earth.worldwind.geom.Matrix4
import kotlin.math.sqrt

/**
 * Decodes an I3S (SLPK) scene layer into a runtime [Tileset] tree — the paged I3S sibling of
 * [TilesetParser]. The hierarchy lives across `nodepages/{n}.json` pulled on demand via a
 * [NodePageSource], so parsing is `suspend`.
 *
 * I3S → runtime model: node `children` indices → [Tile3d.children]; OBB → conservative local
 * [BoundingVolume.Sphere] (radius = OBB diagonal) placed by [Tile3d.tileToWorld] from
 * [GeographicToWorld]; `lodThreshold` (`maxScreenThreshold[SQ]`) → [Tile3d.geometricError] =
 * `referenceSSE·2·radius/threshold` (baked so the traverser's default SSE selects at I3S-native
 * detail). Content decodes separately; [Tile3d.contentUri] points at the geometry via the `slpk:` scheme.
 *
 * Scope: I3S 1.7+ compact node pages, mesh profiles. TODO(scale): builds the tree eagerly —
 * multi-million-node sets should materialise children lazily at traversal (like `SubtreeReader`);
 * [buildNode] is factored for that. TODO(verify): OBB-center CRS + LoD metric on more datasets.
 */
object SceneLayerParser {
    /** Supplies raw `nodepages/{pageIndex}.json` bodies (from the SLPK archive). Null = absent. */
    fun interface NodePageSource {
        suspend fun load(pageIndex: Int): String?
    }

    /** Geographic (WGS84 degrees + ellipsoidal m) → the node's tileToWorld matrix. Backed by the
     *  layer's globe. */
    fun interface GeographicToWorld {
        fun transform(longitudeDeg: Double, latitudeDeg: Double, heightM: Double, result: Matrix4): Matrix4
    }

    suspend fun parse(
        sceneLayerJson: String,
        /** Disambiguates `slpk:` content URIs when several archives are open; also the cache-key prefix. */
        archiveId: String,
        nodePages: NodePageSource,
        geoToWorld: GeographicToWorld,
        /** Baked into [Tile3d.geometricError] so the traverser's default SSE selects at I3S-native
         *  detail. Match the layer's default SSE. */
        referenceScreenSpaceError: Double = 16.0,
    ): Tileset {
        val doc = I3sSceneLayer.parseSceneLayer(sceneLayerJson)
        // Some 1.7/1.8 SLPKs ship nodepages without the `store.nodePages` block — default rather than
        // reject; genuine legacy-only sets surface as a null root page below.
        val pages = doc.store.nodePages
        val nodesPerPage = (pages?.nodesPerPage ?: DEFAULT_NODES_PER_PAGE).coerceAtLeast(1)
        val metricSquared = pages?.lodSelectionMetricType?.endsWith("SQ", ignoreCase = true) == true
        val rootIndex = rootIndexOf(doc)

        val ctx = BuildContext(archiveId, nodesPerPage, metricSquared, referenceScreenSpaceError, nodePages, geoToWorld)
        val rootDoc = ctx.node(rootIndex)
            ?: error("SLPK root node $rootIndex not found — no usable nodepages/*.json (legacy per-node 1.6 index documents are unsupported)")
        val root = ctx.buildNode(rootIndex, rootDoc)
        return Tileset(
            root = root,
            geometricError = root.geometricError,
            gltfUpAxis = null,
            assetVersion = doc.store.version ?: doc.version ?: "1.7",
            extensionsUsed = emptyList(),
        )
    }

    /** Root node index from `store.rootNode` (plain integer; default 0). A non-numeric legacy
     *  `"./nodes/root"` falls through to 0, then surfaces as a null root page in [parse]. */
    private fun rootIndexOf(doc: SceneLayerDoc): Int =
        doc.store.rootNode?.trim()?.toIntOrNull() ?: 0

    /** Per-parse state: page cache + config, so [buildNode] stays a simple recursion. */
    private class BuildContext(
        val archiveId: String,
        val nodesPerPage: Int,
        val metricSquared: Boolean,
        val referenceSSE: Double,
        val source: NodePageSource,
        val geoToWorld: GeographicToWorld,
    ) {
        private val pageCache = HashMap<Int, NodePageDoc>()
        private val visited = HashSet<Int>() // guards malformed cyclic child references

        suspend fun node(index: Int): NodeDoc? {
            if (index < 0) return null
            val pageIndex = index / nodesPerPage
            val page = pageCache[pageIndex] ?: run {
                val body = source.load(pageIndex) ?: return null
                I3sSceneLayer.parseNodePage(body).also { pageCache[pageIndex] = it }
            }
            // Spec orders nodes by index → positional lookup; fall back to a scan if a page is sparse.
            val slot = index % nodesPerPage
            page.nodes.getOrNull(slot)?.let { if (it.index == index || it.index < 0) return it }
            return page.nodes.firstOrNull { it.index == index }
        }

        // Cycle guard keys on [index] (the lookup key), not node.index — pages may omit that field
        // (-1), which would collapse `visited` and let a back-reference recurse forever.
        suspend fun buildNode(index: Int, node: NodeDoc): Tile3d {
            visited.add(index)
            val (boundingVolume, radius) = boundsOf(node)
            val tileToWorld = worldTransformOf(node)
            val children = node.children.mapNotNull { childIndex ->
                if (childIndex in visited) return@mapNotNull null
                node(childIndex)?.let { buildNode(childIndex, it) }
            }
            return Tile3d(
                boundingVolume = boundingVolume,
                geometricError = geometricErrorOf(radius, node.lodThreshold, node.children.isEmpty()),
                refinement = Refinement.REPLACE, // I3S children always replace the parent
                tileToWorld = tileToWorld,
                contentUri = contentUriOf(node),
                children = children,
            )
        }

        /** OBB → local-frame conservative sphere (center at node origin, radius = OBB diagonal).
         *  Returns the sphere plus the radius so the LoD metric can reuse it. */
        private fun boundsOf(node: NodeDoc): Pair<BoundingVolume, Double> {
            val half = node.obb?.halfSize
            val radius = if (half != null && half.size >= 3) {
                sqrt(half[0] * half[0] + half[1] * half[1] + half[2] * half[2])
            } else 0.0
            return BoundingVolume.Sphere(0.0, 0.0, 0.0, radius) to radius
        }

        /** tileToWorld at the OBB center (via [geoToWorld]); identity for a center-less structural node. */
        private fun worldTransformOf(node: NodeDoc): Matrix4 {
            val c = node.obb?.center
            val m = Matrix4()
            if (c == null || c.size < 3) return m
            // I3S center = [longitude, latitude, height].
            return geoToWorld.transform(c[0], c[1], c[2], m)
        }

        /** geometricError = referenceSSE·2·radius/threshold (`…SQ` thresholds square-rooted first). */
        private fun geometricErrorOf(radius: Double, lodThreshold: Double, isLeaf: Boolean): Double {
            if (isLeaf) return 0.0 // nothing finer to refine to
            // Non-leaf with lodThreshold 0 (structural root, no mesh) = "always go finer": force
            // refinement so the child carrying the mesh loads (returning 0 would pin a content-less root).
            if (lodThreshold <= 0.0 || radius <= 0.0) return ALWAYS_REFINE_ERROR
            val threshold = if (metricSquared) sqrt(lodThreshold) else lodThreshold
            return referenceSSE * 2.0 * radius / threshold
        }

        /** `slpk:{id}!/nodes/{resource}/geometries/{definition}.bin`, plus `?t=nodes/{mat}/textures/0.jpg`
         *  when the node has a material (the decoder reads that sibling; the query is stripped before the
         *  archive lookup + cache key). Null for a structural node. `.gz` is resolved by the archive read;
         *  texture 0 = JPEG (1 = KTX2 twin). */
        private fun contentUriOf(node: NodeDoc): String? {
            val geom = node.mesh?.geometry ?: return null
            if (geom.resource < 0) return null
            val base = "slpk:$archiveId!/nodes/${geom.resource}/geometries/${geom.definition}.bin"
            val materialResource = node.mesh?.material?.resource ?: -1
            return if (materialResource >= 0) "$base?t=nodes/$materialResource/textures/0.jpg" else base
        }
    }

    /** Esri's standard node-page size, used when `store.nodePages.nodesPerPage` isn't declared. */
    private const val DEFAULT_NODES_PER_PAGE = 64

    /** Large-but-finite geometricError forcing an always-refine (dominates SSE, avoids Infinity math). */
    private const val ALWAYS_REFINE_ERROR = 1.0e12
}
