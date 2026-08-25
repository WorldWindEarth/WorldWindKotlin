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
    /** Vertical CRS, e.g. 5773 = EGM96 geoid height. See [isGravityRelatedVcsWkid]. */
    val vcsWkid: Int = 0,
    val latestVcsWkid: Int = 0,
    /** CRS as text; the spec allows either [wkid] or this. A compound CRS carries its vertical
     *  datum in an embedded `VERTCS`/`VERTCRS` block. */
    val wkt: String? = null,
)

/** `heightModelInfo`: `heightModel` is `"gravity_related_height"` (orthometric/MSL) or
 *  `"ellipsoidal"` — the spec defines no default; `vertCRS` names the datum (`"EGM96_height"`);
 *  `heightUnit` scales z values, see [heightUnitToMeters]. */
@Serializable
data class HeightModelInfoDoc(
    val heightModel: String? = null,
    val vertCRS: String? = null,
    val heightUnit: String? = null,
)

/** Heights are orthometric (gravity-related/MSL), so an ellipsoid-datum consumer must add the geoid
 *  undulation. Sources in declaration-strength order: `heightModelInfo.heightModel`, the vertical
 *  CRS wkid, an embedded `VERTCS`/`VERTCRS` in `spatialReference.wkt`, then the `vertCRS` name.
 *  Nothing declared means ellipsoidal (HAE), matching how a package without a vertical CRS reads.
 *  The undulation applied downstream is always EGM96, so an EGM2008/NAVD88 package lands within
 *  the sub-metre difference between those geoids. */
fun SceneLayerDoc.usesGravityRelatedHeights(): Boolean {
    heightModelInfo?.heightModel?.let { return it.equals("gravity_related_height", ignoreCase = true) }
    spatialReference?.let { sr ->
        val vcsWkid = if (sr.vcsWkid != 0) sr.vcsWkid else sr.latestVcsWkid
        if (vcsWkid != 0) return isGravityRelatedVcsWkid(vcsWkid)
        sr.wkt?.let { wkt -> verticalWktIsGravityRelated(wkt)?.let { return it } }
    }
    return heightModelInfo?.vertCRS?.let { vertCrsNameIsGravityRelated(it) } ?: false
}

/** A vertical CRS wkid is gravity-related unless it falls in the Esri block defining ellipsoidal
 *  height per datum (115700 = WGS_1984, 115702 = NAD_1983, …). Every EPSG vertical CRS is
 *  gravity-related (3855 = EGM2008, 5703 = NAVD88, 5714 = MSL, 5773 = EGM96), as is the Esri
 *  1057xx block (105700 = WGS_1984_Geoid). */
fun isGravityRelatedVcsWkid(wkid: Int) = wkid != 0 && wkid !in ESRI_ELLIPSOIDAL_VCS_WKIDS

/** Esri wkid block of per-datum ellipsoidal-height vertical CRSs. */
private val ESRI_ELLIPSOIDAL_VCS_WKIDS = 115700..115999

/** Classify a compound-CRS text: a vertical block naming a spheroid is ellipsoidal height, one
 *  naming a vertical datum is gravity-related. Null when the text has no vertical block. */
private fun verticalWktIsGravityRelated(wkt: String): Boolean? {
    val start = VERTICAL_CS_KEYWORD.find(wkt)?.range?.first ?: return null
    val vertical = wkt.substring(start)
    if (SPHEROID_KEYWORD.containsMatchIn(vertical)) return false
    if (VERTICAL_DATUM_KEYWORD.containsMatchIn(vertical)) return true
    return vertCrsNameIsGravityRelated(vertical)
}

/** Metres per `heightModelInfo.heightUnit` — the scale every z value in the package needs to reach
 *  the engine's metres. Applies to OBB centre heights and vertex z offsets, not to `obb.halfSize`,
 *  which the spec fixes in metres. Unset or unrecognised means metres; `"meter"` is what ArcGIS
 *  writes, the rest of the enum comes from surveyed data in legacy units. */
fun SceneLayerDoc.heightUnitToMeters(): Double = when (heightModelInfo?.heightUnit?.lowercase()) {
    // EPSG factors (unit_of_measure): us-foot 9003, foot 9002, clarke 9005/9037/9039, sears
    // 9040/9041/9042, benoit-b 9062, indian 9084/9085, gold coast 9094, sears-truncated 9301.
    "us-foot" -> 0.3048006096012192
    "foot" -> 0.3048
    "clarke-foot" -> 0.3047972654
    "clarke-yard" -> 0.9143917962
    "clarke-link" -> 0.201166195164
    "sears-yard" -> 0.9143984146160287
    "sears-foot" -> 0.3047994715386762
    "sears-chain" -> 20.116765121552632
    "benoit-1895-b-chain" -> 20.116782494375872
    "indian-yard" -> 0.9143985307444408
    "indian-1937-yard" -> 0.91439523
    "gold-coast-foot" -> 0.3047997101815088
    "sears-1922-truncated-chain" -> 20.116756
    "us-inch" -> 0.025400050800101602 // us-foot / 12
    "us-yard" -> 0.9144018288036576 // us-foot * 3
    "us-mile" -> 1609.3472186944375 // us-foot * 5280
    "millimeter" -> 0.001
    "centimeter" -> 0.01
    "decimeter" -> 0.1
    "kilometer" -> 1000.0
    else -> 1.0
}

/** Last-resort read of a free-text vertical CRS name (`EGM96_height`, `NAVD88 height`, …). */
private fun vertCrsNameIsGravityRelated(name: String) = GRAVITY_DATUM_NAME.containsMatchIn(name)

private val VERTICAL_CS_KEYWORD = Regex("""VERT(?:_?CS|CRS)\s*\[""", RegexOption.IGNORE_CASE)
private val VERTICAL_DATUM_KEYWORD = Regex("""V(?:_?DATUM|ERT_DATUM)\s*\[""", RegexOption.IGNORE_CASE)
private val SPHEROID_KEYWORD = Regex("""(?:SPHEROID|ELLIPSOID)\s*\[""", RegexOption.IGNORE_CASE)
private val GRAVITY_DATUM_NAME =
    Regex("""EGM\d*|geoid|orthometric|gravity|NAVD|NGVD|MSL|mean.sea.level""", RegexOption.IGNORE_CASE)

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
