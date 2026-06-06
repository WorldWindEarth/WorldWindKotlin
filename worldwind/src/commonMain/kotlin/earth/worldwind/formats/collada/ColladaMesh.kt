package earth.worldwind.formats.collada

class ColladaMesh(val id: String) {
    internal val _parsedMeshes = mutableListOf<ColladaParsedMesh>()
    val parsedMeshes: List<ColladaParsedMesh> get() = _parsedMeshes

    internal class SourceMeta(val stride: Int, val data: FloatArray)
    /** Raw `<input>` reference before resolving the `<vertices>` indirection. */
    internal class InputDef(val semantic: String, val source: String, val offset: Int, val dataSet: Int)
    /** One `<triangles>`/`<polygons>`/`<polylist>` element as primitive buffers. */
    internal class PrimitiveDef(
        val inputDefs: List<InputDef>, val fixedVCount: Int?, val count: Int,
        val material: String?, val vcount: IntArray, val pData: IntArray
    )

    private class Input(
        val semantic: String, val stride: Int, val rawData: FloatArray,
        val offset: Int, val dataSet: Int
    ) {
        val translatedData = FloatBuf()
    }

    companion object {
        // --- DOM path (small bundled assets); delegates to the shared buffer core ---
        internal fun parse(geometryId: String, element: XmlElement): ColladaMesh {
            val sources = mutableMapOf<String, SourceMeta>()
            val verticesInputs = mutableMapOf<String, Map<String, String>>()
            val primitives = mutableListOf<PrimitiveDef>()

            for (child in element.children) {
                when (child.name) {
                    "source" -> {
                        val floatArray = child.querySelector("float_array") ?: continue
                        val values = ColladaUtils.bufferDataFloat32(floatArray) ?: continue
                        val accessor = child.querySelector("accessor") ?: continue
                        val stride = accessor.getAttribute("stride")?.toIntOrNull() ?: 1
                        sources[child.getAttribute("id") ?: continue] = SourceMeta(stride, values)
                    }
                    "vertices" -> parseVerticesDom(child, verticesInputs)
                    "triangles" -> primitives.add(domPrimitive(child, 3))
                    "polygons" -> primitives.add(domPrimitive(child, 4))
                    "polylist" -> primitives.add(domPrimitive(child, null))
                }
            }
            return buildFromBuffers(geometryId, sources, verticesInputs, primitives)
        }

        private fun parseVerticesDom(element: XmlElement, verticesInputs: MutableMap<String, Map<String, String>>) {
            val id = element.getAttribute("id") ?: return
            val inputs = mutableMapOf<String, String>()
            for (input in element.querySelectorAll("input")) {
                val source = input.getAttribute("source")?.removePrefix("#") ?: continue
                val semantic = (input.getAttribute("semantic") ?: continue).uppercase()
                inputs[semantic] = source
            }
            verticesInputs[id] = inputs
        }

        private fun domPrimitive(element: XmlElement, fixedVCount: Int?): PrimitiveDef {
            val inputDefs = element.querySelectorAll("input").mapNotNull { xmlInput ->
                val semantic = (xmlInput.getAttribute("semantic") ?: return@mapNotNull null).uppercase()
                val sourceUrl = xmlInput.getAttribute("source")?.removePrefix("#") ?: return@mapNotNull null
                val offset = xmlInput.getAttribute("offset")?.toIntOrNull() ?: 0
                val dataSet = xmlInput.getAttribute("set")?.toIntOrNull() ?: 0
                InputDef(semantic, sourceUrl, offset, dataSet)
            }
            val count = element.getAttribute("count")?.toIntOrNull() ?: 0
            val material = element.getAttribute("material")
            val vcount = if (fixedVCount == null) intTokens(element.querySelector("vcount")?.textContent) else IntArray(0)
            val pData = intTokens(element.querySelector("p")?.textContent)
            return PrimitiveDef(inputDefs, fixedVCount, count, material, vcount, pData)
        }

        private fun intTokens(text: String?): IntArray {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return IntArray(0)
            val buf = IntBuf()
            var i = 0
            val n = t.length
            while (i < n) {
                while (i < n && t[i].isWhitespace()) i++
                val start = i
                while (i < n && !t[i].isWhitespace()) i++
                if (i > start) buf.add(t.substring(start, i).toIntOrNull() ?: 0)
            }
            return buf.toArray()
        }

        // --- Shared buffer core: both DOM and streaming paths converge here ---
        internal fun buildFromBuffers(
            geometryId: String,
            sources: Map<String, SourceMeta>,
            verticesInputs: Map<String, Map<String, String>>,
            primitives: List<PrimitiveDef>
        ): ColladaMesh {
            val mesh = ColladaMesh(geometryId)
            for (p in primitives) {
                val (inputs, maxOffset) = resolveInputs(p.inputDefs, sources, verticesInputs)
                mesh._parsedMeshes.add(buildPrimitive(inputs, maxOffset, p))
            }
            return mesh
        }

        private fun resolveInputs(
            defs: List<InputDef>,
            sources: Map<String, SourceMeta>,
            verticesInputs: Map<String, Map<String, String>>
        ): Pair<List<Input>, Int> {
            val inputs = mutableListOf<Input>()
            var maxOffset = 0
            for (def in defs) {
                maxOffset = maxOf(maxOffset, def.offset + 1)
                if (verticesInputs.containsKey(def.source)) {
                    for ((sem, src) in verticesInputs[def.source]!!) {
                        val source = sources[src] ?: continue
                        inputs.add(Input(sem, source.stride, source.data, def.offset, def.dataSet))
                    }
                } else {
                    val source = sources[def.source] ?: continue
                    inputs.add(Input(def.semantic, source.stride, source.data, def.offset, def.dataSet))
                }
            }
            return Pair(inputs, maxOffset)
        }

        private fun buildPrimitive(inputs: List<Input>, maxOffset: Int, prim: PrimitiveDef): ColladaParsedMesh {
            val vcount = prim.vcount
            val fixedVCount = prim.fixedVCount
            val count = prim.count
            val pData = prim.pData

            // Lossless packed dedup key (replaces the per-vertex joinToString String key). Equal
            // index tuples map to equal keys, so dedup grouping is identical to the DOM path.
            var maxIdx = 0
            for (v in pData) if (v > maxIdx) maxIdx = v
            val bits = 32 - maxIdx.countLeadingZeroBits()
            val packable = maxOffset in 1..8 && bits * maxOffset <= 63
            val indexMapLong = if (packable) HashMap<Long, Int>() else null
            val indexMapStr = if (packable) null else HashMap<String, Int>()

            var lastIndex = 0
            val indicesArray = IntBuf()
            var pos = 0
            var isIndexed = false
            var is32Bit = false

            for (i in 0 until count) {
                val numVertices = if (fixedVCount == null) vcount.getOrElse(i) { 0 } else fixedVCount
                var firstIndex = -1
                var currentIndex = -1
                var prevIndex: Int

                for (k in 0 until numVertices) {
                    val key: Any = if (packable) {
                        var p = 0L
                        for (j in 0 until maxOffset) p = (p shl bits) or pData.getOrElse(pos + j) { 0 }.toLong()
                        p
                    } else {
                        val sb = StringBuilder()
                        for (j in 0 until maxOffset) {
                            if (j > 0) sb.append(' ')
                            sb.append(pData.getOrElse(pos + j) { 0 })
                        }
                        sb.toString()
                    }
                    prevIndex = currentIndex
                    val existing = if (packable) indexMapLong!![key as Long] else indexMapStr!![key as String]
                    if (existing != null) {
                        currentIndex = existing
                        isIndexed = true
                    } else {
                        for (input in inputs) {
                            val idx = pData.getOrElse(pos + input.offset) { 0 }
                            val dataIdx = idx * input.stride
                            for (x in 0 until input.stride) {
                                input.translatedData.add(input.rawData.getOrElse(dataIdx + x) { 0f })
                            }
                        }
                        currentIndex = lastIndex++
                        if (packable) indexMapLong!![key as Long] = currentIndex else indexMapStr!![key as String] = currentIndex
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

            val verts = inputs.getOrNull(0)?.translatedData?.toArray() ?: FloatArray(0)
            val parsedMesh = ColladaParsedMesh(
                vertices = verts,
                indexedRendering = isIndexed,
                material = prim.material
            )
            if (isIndexed) {
                parsedMesh.is32BitIndices = is32Bit
                val indices = indicesArray.toArray()
                if (is32Bit) {
                    parsedMesh.indices = indices
                } else {
                    parsedMesh.indicesShort = ShortArray(indices.size) { indices[it].toShort() }
                }
            }
            transformMeshInfo(parsedMesh, inputs)
            return parsedMesh
        }

        private fun transformMeshInfo(mesh: ColladaParsedMesh, inputs: List<Input>) {
            for (i in 1 until inputs.size) {
                val input = inputs[i]
                if (input.translatedData.size == 0) continue
                val data = input.translatedData.toArray()
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
}
