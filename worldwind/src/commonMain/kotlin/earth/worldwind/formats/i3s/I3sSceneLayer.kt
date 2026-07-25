package earth.worldwind.formats.i3s

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * DTOs + decoders for the I3S documents a tile tree is built from: `3dSceneLayer.json` and node pages
 * (`nodepages/{n}.json`). Scope: I3S 1.7+ compact form (node pages) for the hierarchy; legacy 1.6
 * per-node `3dNodeIndexDocument`s are consulted only for texture names when a package omits
 * `textureSetDefinitions`. Only consumed fields are modelled; unknown keys ignored.
 */
object I3sSceneLayer {
    // coerceInputValues: coerce I3S's explicit `null` optional numerics (e.g. texelCountHint) to defaults.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun parseSceneLayer(body: String): SceneLayerDoc = json.decodeFromString(body)

    fun parseNodePage(body: String): NodePageDoc = json.decodeFromString(body)

    fun parseNodeIndex(body: String): NodeIndexDoc = json.decodeFromString(body)

    /** Resolve a node-relative href (`./textures/0_0`) against the node directory into an entry path. */
    fun resolveNodeRelativeHref(nodeDir: String, href: String): String {
        val segments = nodeDir.trimEnd('/').split('/').toMutableList()
        for (part in href.split('/')) when (part) {
            "", "." -> {}
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
            else -> segments.add(part)
        }
        return segments.joinToString("/")
    }
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
    /** Horizontal CRS of OBB centers + vertex offsets: 4326 (degrees, the default) or Web Mercator
     *  (metres). See [I3sWebMercator.isWebMercator]. */
    val spatialReference: SpatialReferenceDoc? = null,
    /** Vertical datum of heights; see [usesGravityRelatedHeights]. */
    val heightModelInfo: HeightModelInfoDoc? = null,
    val store: StoreDoc = StoreDoc(),
    /** 1.7+ material table ([MeshMaterialDoc.definition]); empty in legacy packages, where texture
     *  names come from per-node index documents instead. */
    val materialDefinitions: List<MaterialDefDoc> = emptyList(),
    /** 1.7+ texture sets referenced from [MaterialDefDoc]; `formats[].name` is the archive entry stem. */
    val textureSetDefinitions: List<TextureSetDefDoc> = emptyList(),
    /** 1.7+ geometry table ([MeshGeometryDoc.definition]); each buffer's index is its archive file name
     *  (`geometries/{index}.bin`). Esri convention: buffer 0 = uncompressed, buffer 1 = Draco. */
    val geometryDefinitions: List<GeometryDefDoc> = emptyList(),
)

@Serializable
data class GeometryDefDoc(val geometryBuffers: List<GeometryBufferDoc> = emptyList())

/** One storage form of a geometry: uncompressed SoA when [compressedAttributes] is null. */
@Serializable
data class GeometryBufferDoc(val compressedAttributes: CompressedAttributesDoc? = null)

/** Compressed-buffer descriptor; [encoding] is `"draco"` in every known package. */
@Serializable
data class CompressedAttributesDoc(val encoding: String? = null, val attributes: List<String> = emptyList())

@Serializable
data class MaterialDefDoc(val pbrMetallicRoughness: PbrMetallicRoughnessDoc? = null)

@Serializable
data class PbrMetallicRoughnessDoc(val baseColorTexture: TextureReferenceDoc? = null)

@Serializable
data class TextureReferenceDoc(val textureSetDefinitionId: Int = -1)

@Serializable
data class TextureSetDefDoc(val formats: List<TextureFormatDoc> = emptyList())

/** One texture-set encoding: [name] under `nodes/{res}/textures/`, [format] = `jpg`|`png`|`dds`|`ktx2`. */
@Serializable
data class TextureFormatDoc(val name: String? = null, val format: String? = null)

/** Legacy per-node `3dNodeIndexDocument.json` — only the texture href is consumed. */
@Serializable
data class NodeIndexDoc(val textureData: List<HrefDoc> = emptyList())

@Serializable
data class HrefDoc(val href: String? = null)

@Serializable
data class SpatialReferenceDoc(
    val wkid: Int = 0,
    val latestWkid: Int = 0,
    /** Vertical CRS, e.g. 5773 = EGM96 geoid height. */
    val vcsWkid: Int = 0,
    val latestVcsWkid: Int = 0,
)

/** `heightModelInfo`: `heightModel` is `"gravity_related_height"` (orthometric/MSL, the ArcGIS
 *  default) or `"ellipsoidal"`; `vertCRS` names the datum (e.g. `"EGM96_height"`). */
@Serializable
data class HeightModelInfoDoc(
    val heightModel: String? = null,
    val vertCRS: String? = null,
    val heightUnit: String? = null,
)

/** Heights are orthometric (gravity-related/MSL), so an ellipsoid-datum consumer must add the geoid
 *  undulation. Keyed on `heightModelInfo.heightModel` when declared, else the vertical CRS wkid. */
fun SceneLayerDoc.usesGravityRelatedHeights(): Boolean {
    heightModelInfo?.heightModel?.let { return it.equals("gravity_related_height", ignoreCase = true) }
    val sr = spatialReference ?: return false
    return sr.vcsWkid == EGM96_VCS_WKID || sr.latestVcsWkid == EGM96_VCS_WKID
}

/** EPSG:5773 — EGM96 geoid height. */
private const val EGM96_VCS_WKID = 5773

/** Physical-storage descriptor. */
@Serializable
data class StoreDoc(
    /** Hierarchy entry point. For 1.7 node pages this is the root node index (usually `0`); a
     *  legacy `"./nodes/root"` path signals unsupported 1.6 storage. */
    val rootNode: String? = null,
    /** Storage format version, e.g. `"1.7"` / `"1.8"`. */
    val version: String? = null,
    /** CRS URLs (`…/EPSG/0/{wkid}`); fallback when [SceneLayerDoc.spatialReference] is absent. */
    val indexCRS: String? = null,
    val vertexCRS: String? = null,
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

/** Oriented bounding box. [center] is `[x, y, height]` in the layer's horizontal CRS — geographic
 *  `[longitude°, latitude°]` for 4326, metres for Web Mercator; [halfSize] is CRS-unit metres in the
 *  box's local frame; [quaternion] is `[x, y, z, w]`. */
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
