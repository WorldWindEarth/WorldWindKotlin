package earth.worldwind.layer.ogc3d

import earth.worldwind.layer.ogc3d.tileset.SceneLayerParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Geometry-buffer selection in [SceneLayerParser.parse]: the `.bin` file name is the buffer index
 *  within `geometryDefinitions[definition]`, not the definition index — and Draco-only packages
 *  declare the uncompressed layout without shipping its file, so a Draco-capable parse must pick
 *  the Draco buffer. */
class SceneLayerParserGeometryTest {
    /** FERMA-style definitions table: buffer 0 uncompressed, buffer 1 Draco, for two definitions. */
    private val geometryDefinitions = """"geometryDefinitions":[
        {"geometryBuffers":[
            {"offset":8,"position":{"type":"Float32","component":3}},
            {"compressedAttributes":{"encoding":"draco","attributes":["position","normal","uv0","feature-index"]}}]},
        {"geometryBuffers":[
            {"offset":8,"position":{"type":"Float32","component":3}},
            {"compressedAttributes":{"encoding":"draco","attributes":["position","uv0","feature-index"]}}]}
    ]"""

    private fun nodePage(definition: Int) = """{"nodes":[{
        "index":0,"lodThreshold":100.0,
        "obb":{"center":[10.0,20.0,30.0],"halfSize":[1.0,1.0,1.0],"quaternion":[0.0,0.0,0.0,1.0]},
        "children":[],
        "mesh":{"geometry":{"definition":$definition,"resource":7,"vertexCount":3,"featureCount":1}}
    }]}"""

    private suspend fun parse(sceneLayerJson: String, definition: Int, preferDraco: Boolean) = SceneLayerParser.parse(
        sceneLayerJson = sceneLayerJson,
        archiveId = "test.slpk",
        nodePages = { pageIndex -> if (pageIndex == 0) nodePage(definition) else null },
        geoToWorld = { _, _, _, result -> result },
        preferDracoGeometry = preferDraco,
    )

    private val sceneLayer = """{"store":{"rootNode":"0","version":"1.10","nodePages":{"nodesPerPage":64}},$geometryDefinitions}"""

    @Test fun dracoCapableParsePicksDracoBuffer() = runTest {
        // Regardless of definition index, the Draco buffer is file 1 in this table.
        for (definition in 0..1) {
            val tileset = parse(sceneLayer, definition, preferDraco = true)
            assertEquals("slpk:test.slpk!/nodes/7/geometries/1.bin", tileset.root.contentUri, "definition $definition")
        }
    }

    @Test fun withoutDracoDecoderPicksUncompressedBuffer() = runTest {
        for (definition in 0..1) {
            val tileset = parse(sceneLayer, definition, preferDraco = false)
            assertEquals("slpk:test.slpk!/nodes/7/geometries/0.bin", tileset.root.contentUri, "definition $definition")
        }
    }

    @Test fun legacyPackageWithoutDefinitionsKeepsDefinitionAsFileName() = runTest {
        val legacy = """{"store":{"rootNode":"0","version":"1.7","nodePages":{"nodesPerPage":64}}}"""
        assertEquals(
            "slpk:test.slpk!/nodes/7/geometries/1.bin",
            parse(legacy, definition = 1, preferDraco = true).root.contentUri,
        )
    }
}
