package earth.worldwind.formats.i3s

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * DTOs + decoders for the I3S documents a tile tree is built from: `3dSceneLayer.json` and node pages
 * (`nodepages/{n}.json`). Scope: I3S 1.7+ compact form (node pages); legacy 1.6 per-node index
 * documents are unsupported. Only consumed fields are modelled; unknown keys ignored.
 */
object I3sSceneLayer {
    // coerceInputValues: coerce I3S's explicit `null` optional numerics (e.g. texelCountHint) to defaults.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun parseSceneLayer(body: String): SceneLayerDoc = json.decodeFromString(body)

    fun parseNodePage(body: String): NodePageDoc = json.decodeFromString(body)
}

/** Root `3dSceneLayer.json` document. */
@Serializable
data class SceneLayerDoc(
    val id: Int? = null,
    /** Update-session id; not a format version. Store format version lives in [StoreDoc.version]. */
    val version: String? = null,
    val name: String? = null,
    /** `IntegratedMesh` | `3DObject` | `Point` | `PointCloud` | `Building` (only mesh types targeted). */
    val layerType: String? = null,
    val store: StoreDoc = StoreDoc(),
)

/** Physical-storage descriptor. */
@Serializable
data class StoreDoc(
    /** Hierarchy entry point. For 1.7 node pages this is the root node index (usually `0`); a
     *  legacy `"./nodes/root"` path signals unsupported 1.6 storage. */
    val rootNode: String? = null,
    /** Storage format version, e.g. `"1.7"` / `"1.8"`. */
    val version: String? = null,
    val nodePages: NodePagesDoc? = null,
)

/** Node-page pagination + LoD metric config. */
@Serializable
data class NodePagesDoc(
    val nodesPerPage: Int = 64,
    /** `maxScreenThreshold` (linear px) or `maxScreenThresholdSQ` (px²). Drives per-node
     *  [NodeDoc.lodThreshold] interpretation. */
    val lodSelectionMetricType: String? = null,
)

/** One `nodepages/{n}.json` file: a flattened slice of the node tree. */
@Serializable
data class NodePageDoc(val nodes: List<NodeDoc> = emptyList())

/** One node within a page. */
@Serializable
data class NodeDoc(
    /** Global node index (also its position: page = index / nodesPerPage). */
    val index: Int = -1,
    val parentIndex: Int = -1,
    /** LoD selection metric value; interpreted per [NodePagesDoc.lodSelectionMetricType]. 0 = leaf. */
    val lodThreshold: Double = 0.0,
    val obb: ObbDoc? = null,
    /** Global indices of child nodes. */
    val children: List<Int> = emptyList(),
    val mesh: MeshDoc? = null,
)

/** Oriented bounding box. For global scenes [center] is geographic `[longitude, latitude, height]`
 *  (WGS84 degrees + ellipsoidal meters); [halfSize] is meters in the box's local ENU frame;
 *  [quaternion] is `[x, y, z, w]`. */
@Serializable
data class ObbDoc(
    val center: List<Double> = emptyList(),
    val halfSize: List<Double> = emptyList(),
    val quaternion: List<Double> = emptyList(),
)

/** Mesh resource references for a node. */
@Serializable
data class MeshDoc(
    val geometry: MeshGeometryDoc? = null,
    val material: MeshMaterialDoc? = null,
    val attribute: MeshResourceRef? = null,
)

@Serializable
data class MeshGeometryDoc(
    /** Index into `store.defaultGeometrySchema` / geometry definitions — the geometry layer id. */
    val definition: Int = 0,
    /** Resource id used to build the geometry archive path. -1 = no geometry (structural node). */
    val resource: Int = -1,
    val vertexCount: Int = 0,
    val featureCount: Int = 0,
)

@Serializable
data class MeshMaterialDoc(
    val definition: Int = -1,
    val resource: Int = -1,
    val texelCountHint: Int = 0,
)

@Serializable
data class MeshResourceRef(val resource: Int = -1)
