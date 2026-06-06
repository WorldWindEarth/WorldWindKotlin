package earth.worldwind.formats.collada

import dev.icerock.moko.resources.AssetResource
import dev.icerock.moko.resources.ResourceContainer
import earth.worldwind.geom.Position
import earth.worldwind.render.RenderResourceCache
import earth.worldwind.util.assetPath
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ColladaLoader(val position: Position, val dirPath: String) {
    private var asset: AssetResource? = null

    constructor(position: Position, asset: AssetResource) : this(
        position, asset.assetPath.substringBeforeLast("/", "").let { if (it.isEmpty()) "" else "$it/" }
    ) { this.asset = asset }

    suspend fun parse(assets : ResourceContainer<AssetResource>, rrc: RenderResourceCache): ColladaScene {
        val a = requireNotNull(asset) { "ColladaLoader.parse(rrc) requires AssetResource constructor" }
        val data = suspendCancellableCoroutine { cont -> rrc.retrieveTextAsset(a) { cont.resume(it) } }
        return parse(data).apply { imageSourceFactory = { path -> rrc.imageSourceFromAssetPath(assets, path) } }
    }

    fun parse(data: String): ColladaScene {
        val catalog = ColladaSceneCatalog()
        val xmlRoot = XmlElement.parse(data)
        populateCatalog(xmlRoot, catalog)
        return ColladaScene(position, dirPath, catalog, unitScale(xmlRoot), upAxis(xmlRoot))
    }
}

/** Populates [catalog] from a (full or structural-only) COLLADA DOM. Geometry libraries absent from
 *  [xmlRoot] are simply skipped, so the streaming parser can pre-fill `catalog.meshes` and pass a
 *  geometry-free root here for nodes/materials/images. */
internal fun populateCatalog(xmlRoot: XmlElement, catalog: ColladaSceneCatalog) {
    val iNodes = xmlRoot.getElementsByTagName("library_nodes").flatMap { lib ->
        lib.children.filter { it.name == "node" }
    }
    val eNodes = xmlRoot.getElementsByTagName("library_effects").flatMap { lib ->
        lib.children.filter { it.name == "effect" }
    }
    parseLib(xmlRoot, catalog, "visual_scene", iNodes)
    parseLib(xmlRoot, catalog, "library_geometries", emptyList())
    parseLib(xmlRoot, catalog, "library_materials", eNodes)
    parseLib(xmlRoot, catalog, "library_images", emptyList())
}

/** DOM-path catalog build (used for streaming-vs-DOM equivalence tests). */
internal fun parseCatalogDom(data: String): ColladaSceneCatalog =
    ColladaSceneCatalog().also { populateCatalog(XmlElement.parse(data), it) }

internal fun unitScale(xmlRoot: XmlElement) =
    xmlRoot.getElementsByTagName("unit").firstOrNull()?.getAttribute("meter")?.toDoubleOrNull() ?: 1.0

/** COLLADA `<asset><up_axis>`; defaults to [ColladaUpAxis.Y_UP] per the COLLADA spec. */
internal fun upAxis(xmlRoot: XmlElement) =
    ColladaUpAxis.fromString(xmlRoot.getElementsByTagName("up_axis").firstOrNull()?.textContent)

private fun parseLib(xmlRoot: XmlElement, catalog: ColladaSceneCatalog, libName: String, extraNodes: List<XmlElement>) {
    val libs = xmlRoot.getElementsByTagName(libName)
    if (libs.isEmpty()) return
    val libNodes = libs[0].children

    for (libNode in libNodes) {
        when (libNode.name) {
            "node" -> {
                val nodes = ColladaNode.parse(libNode, extraNodes)
                catalog.children.addAll(nodes)
            }
            "geometry" -> {
                val geometryId = libNode.getAttribute("id") ?: continue
                val xmlMesh = libNode.querySelector("mesh") ?: continue
                catalog.meshes[geometryId] = ColladaMesh.parse(geometryId, xmlMesh)
            }
            "material" -> {
                val materialId = libNode.getAttribute("id") ?: continue
                val iEffect = libNode.querySelector("instance_effect") ?: continue
                val effectId = iEffect.getAttribute("url")?.removePrefix("#") ?: continue
                val effect = ColladaUtils.querySelectorById(extraNodes, effectId) ?: continue
                catalog.materials[materialId] = ColladaMaterial.parse(materialId, effect)
            }
            "image" -> {
                val imageId = libNode.getAttribute("id") ?: continue
                val imageName = libNode.getAttribute("name") ?: imageId
                catalog.images[imageId] = ColladaImage.parse(imageId, imageName, libNode)
            }
        }
    }
}
