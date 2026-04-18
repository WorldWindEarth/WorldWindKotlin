package earth.worldwind.formats.collada

class ColladaMesh(val id: String) {
    private val _parsedMeshes = mutableListOf<ColladaParsedMesh>()
    val parsedMeshes: List<ColladaParsedMesh> get() = _parsedMeshes

    private class SourceMeta(val stride: Int, val data: FloatArray)
    private class Input(
        val semantic: String, val stride: Int, val rawData: FloatArray,
        val offset: Int, val dataSet: Int
    ) {
        val translatedData = mutableListOf<Float>()
    }

    companion object {
        internal fun parse(geometryId: String, element: XmlElement): ColladaMesh {
            val mesh = ColladaMesh(geometryId)
            val sources = mutableMapOf<String, SourceMeta>()
            val verticesInputs = mutableMapOf<String, MutableMap<String, String>>()

            for (child in element.children) {
                when (child.name) {
                    "source" -> {
                        val floatArray = child.querySelector("float_array") ?: continue
                        val values = ColladaUtils.bufferDataFloat32(floatArray) ?: continue
                        val accessor = child.querySelector("accessor") ?: continue
                        val stride = accessor.getAttribute("stride")?.toIntOrNull() ?: 1
                        sources[child.getAttribute("id") ?: continue] = SourceMeta(stride, values)
                    }
                    "vertices" -> mesh.parseVertices(child, verticesInputs)
                    "triangles" -> mesh._parsedMeshes.add(mesh.parsePolygons(child, sources, verticesInputs, 3))
                    "polygons" -> mesh._parsedMeshes.add(mesh.parsePolygons(child, sources, verticesInputs, 4))
                    "polylist" -> mesh._parsedMeshes.add(mesh.parsePolygons(child, sources, verticesInputs, null))
                }
            }
            return mesh
        }
    }

    private fun parseVertices(element: XmlElement, verticesInputs: MutableMap<String, MutableMap<String, String>>) {
        val id = element.getAttribute("id") ?: return
        val inputs = mutableMapOf<String, String>()
        for (input in element.querySelectorAll("input")) {
            val source = input.getAttribute("source")?.removePrefix("#") ?: continue
            val semantic = (input.getAttribute("semantic") ?: continue).uppercase()
            inputs[semantic] = source
        }
        verticesInputs[id] = inputs
    }

    private fun parsePolygons(
        element: XmlElement,
        sources: Map<String, SourceMeta>,
        verticesInputs: Map<String, Map<String, String>>,
        fixedVCount: Int?
    ): ColladaParsedMesh {
        val arrVcount = if (fixedVCount == null) {
            element.querySelector("vcount")?.textContent?.trim()?.split(" ") ?: emptyList()
        } else emptyList()

        val count = element.getAttribute("count")?.toIntOrNull() ?: 0
        val material = element.getAttribute("material")

        val inputData = parseInputs(element, sources, verticesInputs)
        val inputs = inputData.first
        val maxOffset = inputData.second

        val primText = element.querySelector("p")?.textContent?.trim() ?: ""
        val primitiveData = if (primText.isNotEmpty()) primText.split(" ") else emptyList()

        var lastIndex = 0
        val indexMap = mutableMapOf<String, Int>()
        val indicesArray = mutableListOf<Int>()
        var pos = 0
        var isIndexed = false
        var is32Bit = false

        for (i in 0 until count) {
            val numVertices = if (arrVcount.isNotEmpty()) arrVcount[i].toInt() else fixedVCount!!
            var firstIndex = -1
            var currentIndex = -1
            var prevIndex: Int

            for (k in 0 until numVertices) {
                val vecId = primitiveData.subList(pos, minOf(pos + maxOffset, primitiveData.size)).joinToString(" ")
                prevIndex = currentIndex
                if (indexMap.containsKey(vecId)) {
                    currentIndex = indexMap[vecId]!!
                    isIndexed = true
                } else {
                    for (input in inputs) {
                        val idx = primitiveData.getOrNull(pos + input.offset)?.toIntOrNull() ?: 0
                        val dataIdx = idx * input.stride
                        for (x in 0 until input.stride) {
                            input.translatedData.add(input.rawData.getOrElse(dataIdx + x) { 0f })
                        }
                    }
                    currentIndex = lastIndex++
                    indexMap[vecId] = currentIndex
                }

                if (numVertices > 3) {
                    if (k == 0) firstIndex = currentIndex
                    if (k > 2) {
                        if (firstIndex > 65535 || prevIndex > 65535) is32Bit = true
                        indicesArray.add(firstIndex)
                        indicesArray.add(prevIndex)
                    }
                }
                if (currentIndex > 65535) is32Bit = true
                indicesArray.add(currentIndex)
                pos += maxOffset
            }
        }

        val parsedMesh = ColladaParsedMesh(
            vertices = FloatArray(inputs.getOrNull(0)?.translatedData?.size ?: 0) { inputs[0].translatedData[it] },
            indexedRendering = isIndexed,
            material = material
        )
        if (isIndexed) {
            parsedMesh.is32BitIndices = is32Bit
            if (is32Bit) {
                parsedMesh.indices = indicesArray.toIntArray()
            } else {
                parsedMesh.indicesShort = ShortArray(indicesArray.size) { indicesArray[it].toShort() }
            }
        }
        transformMeshInfo(parsedMesh, inputs)
        return parsedMesh
    }

    private fun parseInputs(
        element: XmlElement,
        sources: Map<String, SourceMeta>,
        verticesInputs: Map<String, Map<String, String>>
    ): Pair<List<Input>, Int> {
        val inputs = mutableListOf<Input>()
        var maxOffset = 0

        for (xmlInput in element.querySelectorAll("input")) {
            val semantic = (xmlInput.getAttribute("semantic") ?: continue).uppercase()
            val sourceUrl = xmlInput.getAttribute("source")?.removePrefix("#") ?: continue
            val offset = xmlInput.getAttribute("offset")?.toIntOrNull() ?: 0
            val dataSet = xmlInput.getAttribute("set")?.toIntOrNull() ?: 0
            maxOffset = maxOf(maxOffset, offset + 1)

            if (verticesInputs.containsKey(sourceUrl)) {
                val vInputs = verticesInputs[sourceUrl]!!
                for ((sem, src) in vInputs) {
                    val source = sources[src] ?: continue
                    inputs.add(Input(sem, source.stride, source.data, offset, dataSet))
                }
            } else {
                val source = sources[sourceUrl] ?: continue
                inputs.add(Input(semantic, source.stride, source.data, offset, dataSet))
            }
        }
        return Pair(inputs, maxOffset)
    }

    private fun transformMeshInfo(mesh: ColladaParsedMesh, inputs: List<Input>) {
        for (i in 1 until inputs.size) {
            val input = inputs[i]
            if (input.translatedData.isEmpty()) continue
            val data = FloatArray(input.translatedData.size) { input.translatedData[it] }
            when (input.semantic.lowercase()) {
                "normal" -> mesh.normals = data
                "texcoord" -> {
                    mesh.uvs = data
                    mesh.clamp = ColladaUtils.getTextureType(data)
                }
            }
        }
    }
}
