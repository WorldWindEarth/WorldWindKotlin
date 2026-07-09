package earth.worldwind.layer.ogc3d

import earth.worldwind.formats.i3s.I3sSceneLayer
import earth.worldwind.layer.ogc3d.tileset.SceneLayerParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Texture-URI resolution in [SceneLayerParser.parse]: entry names must come from package metadata
 *  (`textureSetDefinitions` or, for legacy-profile packages, a `?tn=` marker), never be invented. */
class SceneLayerParserTextureTest {
    private val nodePage = """{"nodes":[{
        "index":0,"lodThreshold":100.0,
        "obb":{"center":[10.0,20.0,30.0],"halfSize":[1.0,1.0,1.0],"quaternion":[0.0,0.0,0.0,1.0]},
        "children":[],
        "mesh":{"geometry":{"definition":0,"resource":0,"vertexCount":3,"featureCount":1},
                "material":{"definition":0,"resource":0}}
    }]}"""

    private suspend fun parse(sceneLayerJson: String) = SceneLayerParser.parse(
        sceneLayerJson = sceneLayerJson,
        archiveId = "test.slpk",
        nodePages = { pageIndex -> if (pageIndex == 0) nodePage else null },
        geoToWorld = { _, _, _, result -> result },
    )

    @Test fun textureNameFromTextureSetDefinitions() = runTest {
        val tileset = parse("""{
            "store":{"rootNode":"0","version":"1.7","nodePages":{"nodesPerPage":64}},
            "materialDefinitions":[{"pbrMetallicRoughness":{"baseColorTexture":{"textureSetDefinitionId":0}}}],
            "textureSetDefinitions":[{"formats":[{"name":"0","format":"jpg"},{"name":"1","format":"ktx2"}]}]
        }""")
        assertEquals("slpk:test.slpk!/nodes/0/geometries/0.bin?t=nodes/0/textures/0.jpg", tileset.root.contentUri)
    }

    @Test fun decodableFormatPreferredOverCompressed() = runTest {
        val tileset = parse("""{
            "store":{"rootNode":"0","version":"1.7","nodePages":{"nodesPerPage":64}},
            "materialDefinitions":[{"pbrMetallicRoughness":{"baseColorTexture":{"textureSetDefinitionId":0}}}],
            "textureSetDefinitions":[{"formats":[{"name":"1","format":"ktx2"},{"name":"0","format":"png"}]}]
        }""")
        assertEquals("slpk:test.slpk!/nodes/0/geometries/0.bin?t=nodes/0/textures/0.png", tileset.root.contentUri)
    }

    @Test fun legacyPackageWithoutDefinitionsGetsNodeMarker() = runTest {
        // Metashape-style hybrid: nodepages present, materialDefinitions/textureSetDefinitions absent.
        val tileset = parse("""{"store":{"rootNode":"0","version":"1.7"}}""")
        assertEquals("slpk:test.slpk!/nodes/0/geometries/0.bin?tn=0", tileset.root.contentUri)
    }

    @Test fun untexturedMaterialGetsNoTextureQuery() = runTest {
        val tileset = parse("""{
            "store":{"rootNode":"0","version":"1.7","nodePages":{"nodesPerPage":64}},
            "materialDefinitions":[{"pbrMetallicRoughness":{}}],
            "textureSetDefinitions":[]
        }""")
        assertEquals("slpk:test.slpk!/nodes/0/geometries/0.bin", tileset.root.contentUri)
    }

    @Test fun nodeRelativeHrefResolution() {
        assertEquals("nodes/3/textures/0_0", I3sSceneLayer.resolveNodeRelativeHref("nodes/3", "./textures/0_0"))
        assertEquals("nodes/3/textures/0_0", I3sSceneLayer.resolveNodeRelativeHref("nodes/3", "textures/0_0"))
        assertEquals("nodes/4/textures/0_0", I3sSceneLayer.resolveNodeRelativeHref("nodes/3", "../4/textures/0_0"))
    }

    @Test fun nodeIndexDocumentTextureHref() {
        val doc = I3sSceneLayer.parseNodeIndex("""{"id":"0","textureData":[{"href":"./textures/0_0"}]}""")
        assertEquals("./textures/0_0", doc.textureData.first().href)
    }
}
