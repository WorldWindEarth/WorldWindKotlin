package earth.worldwind.formats.collada

class ColladaSceneCatalog {
    val images = mutableMapOf<String, ColladaImage>()
    val materials = mutableMapOf<String, ColladaMaterial>()
    val meshes = mutableMapOf<String, ColladaMesh>()
    val children = mutableListOf<ColladaNode>()
}