@file:OptIn(XmlUtilInternal::class)

package earth.worldwind.formats.collada

import earth.worldwind.geom.Position
import nl.adaptivity.xmlutil.XmlUtilInternal
import nl.adaptivity.xmlutil.core.impl.multiplatform.Reader

/**
 * Low-peak-memory COLLADA parser. Streams the document once from [Reader]: the heavy
 * `library_geometries` numeric bodies are tokenized straight into primitive buffers (never a
 * String or DOM), while the small structural libraries (visual_scene, nodes, effects, materials,
 * images, asset) are buffered as a lightweight DOM so the existing [populateCatalog] logic and its
 * forward-reference resolution are reused verbatim. Use for large `.dae`/`.kmz` (jvmCommon).
 */
internal object ColladaStreamParser {
    fun parse(position: Position, dirPath: String, reader: Reader): ColladaScene {
        val (catalog, structuralRoot) = buildCatalog(reader)
        return ColladaScene(position, dirPath, catalog, unitScale(structuralRoot), upAxis(structuralRoot))
    }

    /** Streams [reader] into a catalog plus the small structural DOM (the latter only for unit scale). */
    internal fun buildCatalog(reader: Reader): Pair<ColladaSceneCatalog, XmlElement> {
        val catalog = ColladaSceneCatalog()
        val tok = ColladaTokenizer(reader)
        val structuralRoot = XmlElement("COLLADA", emptyMap())

        // Advance to <COLLADA> (skips <?xml?>, comments, leading whitespace).
        var t = tok.next()
        while (t != XmlToken.EOF && !(t == XmlToken.START && tok.name == "COLLADA")) t = tok.next()

        // Iterate <COLLADA> children: stream geometries, buffer everything else as a small DOM.
        if (t == XmlToken.START) {
            loop@ while (true) {
                when (tok.next()) {
                    XmlToken.START ->
                        if (tok.name == "library_geometries") streamGeometries(tok, catalog)
                        else structuralRoot.children.add(buildElement(tok))
                    XmlToken.END, XmlToken.EOF -> break@loop
                    XmlToken.TEXT -> {}
                }
            }
        }

        populateCatalog(structuralRoot, catalog)
        return Pair(catalog, structuralRoot)
    }

    /** Builds an [XmlElement] subtree for the element whose START [tok] just returned. */
    private fun buildElement(tok: ColladaTokenizer): XmlElement {
        val el = XmlElement(tok.name, tok.copyAttributes())
        while (true) {
            when (tok.next()) {
                XmlToken.START -> el.children.add(buildElement(tok))
                XmlToken.TEXT -> if (tok.text.isNotBlank()) el.textContent += tok.text
                XmlToken.END, XmlToken.EOF -> return el
            }
        }
    }

    /** Consumes the current element's remaining subtree (START already returned). */
    private fun skipElement(tok: ColladaTokenizer) {
        var depth = 1
        while (depth > 0) {
            when (tok.next()) {
                XmlToken.START -> depth++
                XmlToken.END -> depth--
                XmlToken.EOF -> return
                XmlToken.TEXT -> {}
            }
        }
    }

    private fun streamGeometries(tok: ColladaTokenizer, catalog: ColladaSceneCatalog) {
        while (true) {
            when (tok.next()) {
                XmlToken.START -> if (tok.name == "geometry") streamGeometry(tok, catalog) else skipElement(tok)
                XmlToken.END, XmlToken.EOF -> return
                XmlToken.TEXT -> {}
            }
        }
    }

    private fun streamGeometry(tok: ColladaTokenizer, catalog: ColladaSceneCatalog) {
        val geometryId = tok.attr("id")
        val sources = mutableMapOf<String, ColladaMesh.SourceMeta>()
        val verticesInputs = mutableMapOf<String, Map<String, String>>()
        val primitives = mutableListOf<ColladaMesh.PrimitiveDef>()
        while (true) {
            when (tok.next()) {
                XmlToken.START -> if (tok.name == "mesh") streamMesh(tok, sources, verticesInputs, primitives) else skipElement(tok)
                XmlToken.END, XmlToken.EOF -> break
                XmlToken.TEXT -> {}
            }
        }
        if (geometryId != null) {
            catalog.meshes[geometryId] = ColladaMesh.buildFromBuffers(geometryId, sources, verticesInputs, primitives)
        }
    }

    private fun streamMesh(
        tok: ColladaTokenizer,
        sources: MutableMap<String, ColladaMesh.SourceMeta>,
        verticesInputs: MutableMap<String, Map<String, String>>,
        primitives: MutableList<ColladaMesh.PrimitiveDef>
    ) {
        while (true) {
            when (tok.next()) {
                XmlToken.START -> when (tok.name) {
                    "source" -> streamSource(tok, sources)
                    "vertices" -> streamVertices(tok, verticesInputs)
                    "triangles" -> primitives.add(streamPrimitive(tok, 3))
                    "polygons" -> primitives.add(streamPrimitive(tok, 4))
                    "polylist" -> primitives.add(streamPrimitive(tok, null))
                    else -> skipElement(tok)
                }
                XmlToken.END, XmlToken.EOF -> return
                XmlToken.TEXT -> {}
            }
        }
    }

    private fun streamSource(tok: ColladaTokenizer, sources: MutableMap<String, ColladaMesh.SourceMeta>) {
        val id = tok.attr("id")
        var data: FloatArray? = null
        var stride = 1
        // <accessor> is nested under <technique_common>, so walk the whole <source> subtree by depth.
        var depth = 1
        while (depth > 0) {
            when (tok.next()) {
                XmlToken.START -> when (tok.name) {
                    "float_array" -> { val fb = FloatBuf(); tok.readFloats(fb); data = fb.toArray(); tok.next() } // float_array START+END both consumed here
                    "accessor" -> { stride = tok.attr("stride")?.toIntOrNull() ?: 1; depth++ }
                    else -> depth++
                }
                XmlToken.END -> depth--
                XmlToken.EOF -> break
                XmlToken.TEXT -> {}
            }
        }
        if (id != null && data != null) sources[id] = ColladaMesh.SourceMeta(stride, data)
    }

    private fun streamVertices(tok: ColladaTokenizer, verticesInputs: MutableMap<String, Map<String, String>>) {
        val id = tok.attr("id")
        val inputs = mutableMapOf<String, String>()
        while (true) {
            when (tok.next()) {
                XmlToken.START -> {
                    if (tok.name == "input") {
                        val sem = tok.attr("semantic")?.uppercase()
                        val src = tok.attr("source")?.removePrefix("#")
                        if (sem != null && src != null) inputs[sem] = src
                    }
                    skipElement(tok)
                }
                XmlToken.END, XmlToken.EOF -> break
                XmlToken.TEXT -> {}
            }
        }
        if (id != null) verticesInputs[id] = inputs
    }

    private fun streamPrimitive(tok: ColladaTokenizer, fixedVCount: Int?): ColladaMesh.PrimitiveDef {
        val count = tok.attr("count")?.toIntOrNull() ?: 0
        val material = tok.attr("material")
        val inputDefs = mutableListOf<ColladaMesh.InputDef>()
        var vcount = IntArray(0)
        var pData = IntArray(0)
        while (true) {
            when (tok.next()) {
                XmlToken.START -> when (tok.name) {
                    "input" -> {
                        val sem = tok.attr("semantic")?.uppercase()
                        val src = tok.attr("source")?.removePrefix("#")
                        val offset = tok.attr("offset")?.toIntOrNull() ?: 0
                        val set = tok.attr("set")?.toIntOrNull() ?: 0
                        if (sem != null && src != null) inputDefs.add(ColladaMesh.InputDef(sem, src, offset, set))
                        skipElement(tok)
                    }
                    "vcount" -> { val ib = IntBuf(); tok.readInts(ib); vcount = ib.toArray(); tok.next() }
                    "p" -> { val ib = IntBuf(); tok.readInts(ib); pData = ib.toArray(); tok.next() }
                    else -> skipElement(tok)
                }
                XmlToken.END, XmlToken.EOF -> break
                XmlToken.TEXT -> {}
            }
        }
        return ColladaMesh.PrimitiveDef(inputDefs, fixedVCount, count, material, vcount, pData)
    }
}
