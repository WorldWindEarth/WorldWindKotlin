package earth.worldwind.formats.collada

import earth.worldwind.geom.Position

class ColladaLoader(val position: Position, dirPath: String = "/") {
    var dirPath: String = dirPath
        private set
    private lateinit var xmlRoot: XmlElement
    private lateinit var catalog: ColladaSceneCatalog

    fun init(newDirPath: String = "/") {
        dirPath = newDirPath
    }

    fun parse(data: String): ColladaScene {
        catalog = ColladaSceneCatalog()
        xmlRoot = XmlElement.parse(data)

        val iNodes = xmlRoot.getElementsByTagName("library_nodes").flatMap { lib ->
            lib.children.filter { it.name == "node" }
        }
        val eNodes = xmlRoot.getElementsByTagName("library_effects").flatMap { lib ->
            lib.children.filter { it.name == "effect" }
        }

        parseLib("visual_scene", iNodes)
        parseLib("library_geometries", emptyList())
        parseLib("library_materials", eNodes)
        parseLib("library_images", emptyList())

        val unitScale = xmlRoot.getElementsByTagName("unit").firstOrNull()
            ?.getAttribute("meter")?.toDoubleOrNull() ?: 1.0

        return ColladaScene(position, dirPath, catalog, unitScale)
    }

    private fun parseLib(libName: String, extraNodes: List<XmlElement>) {
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
}
