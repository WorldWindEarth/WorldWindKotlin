package earth.worldwind.formats.collada

class ColladaParsedMesh(
    var vertices: FloatArray,
    val indexedRendering: Boolean,
    val material: String?
) {
    var indices: IntArray? = null        // null means non-indexed rendering
    var indicesShort: ShortArray? = null // prefer short for small meshes
    var is32BitIndices: Boolean = false
    var normals: FloatArray? = null
    var uvs: FloatArray? = null
    var clamp: Boolean = false
    var normalsComputed: Boolean = false
}