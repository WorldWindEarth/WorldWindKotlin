package earth.worldwind.layer.ogc3d.tileset

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingTilesetReaderTest {

    @Test
    fun parsesMinimalGooglePhotorealShape() {
        val body = """
            {
              "asset": { "version": "1.0", "gltfUpAxis": "Z" },
              "geometricError": 1000.0,
              "extensionsUsed": ["GOOGLE_CESIUM_3D_TILES_ATTRIBUTION"],
              "root": {
                "boundingVolume": { "region": [-1.23, 0.5, -1.21, 0.7, 0.0, 100.0] },
                "geometricError": 500.0,
                "refine": "REPLACE",
                "transform": [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1],
                "content": { "uri": "tile_0.json" },
                "children": [
                  {
                    "boundingVolume": { "box": [0,0,0, 1,0,0, 0,1,0, 0,0,1] },
                    "geometricError": 100.0,
                    "content": { "uri": "a.b3dm" }
                  }
                ]
              }
            }
        """.trimIndent()
        val doc = StreamingTilesetReader(body).parseTileset()
        assertEquals("1.0", doc.asset.version)
        assertEquals("Z", doc.asset.gltfUpAxis)
        assertEquals(1000.0, doc.geometricError)
        assertEquals(listOf("GOOGLE_CESIUM_3D_TILES_ATTRIBUTION"), doc.extensionsUsed)
        assertEquals(6, doc.root.boundingVolume.region.size)
        assertEquals(500.0, doc.root.geometricError)
        assertEquals("REPLACE", doc.root.refine)
        assertEquals(16, doc.root.transform.size)
        assertEquals("tile_0.json", doc.root.content?.uri)
        assertEquals(1, doc.root.children.size)
        assertEquals(12, doc.root.children[0].boundingVolume.box.size)
        assertEquals("a.b3dm", doc.root.children[0].content?.uri)
    }

    @Test
    fun handlesBareNonFiniteTokens() {
        // 3D BAG style: bare NaN / Infinity in number positions.
        val body = """
            {
              "asset": { "version": "1.1" },
              "geometricError": NaN,
              "root": {
                "boundingVolume": { "sphere": [0, 0, 0, Infinity] },
                "geometricError": -Infinity,
                "content": { "uri": "x.b3dm" }
              }
            }
        """.trimIndent()
        val doc = StreamingTilesetReader(body).parseTileset()
        assertTrue(doc.geometricError.isNaN())
        assertEquals(Double.POSITIVE_INFINITY, doc.root.boundingVolume.sphere[3])
        assertEquals(Double.NEGATIVE_INFINITY, doc.root.geometricError)
    }

    @Test
    fun handlesQuotedNonFiniteTokens() {
        // 3D BAG style: quoted "NaN" / "Infinity" / "-Infinity" in number positions.
        val body = """
            {
              "asset": { "version": "1.0" },
              "geometricError": "NaN",
              "root": {
                "boundingVolume": { "sphere": [0, 0, 0, "Infinity"] },
                "geometricError": "-Infinity",
                "content": { "uri": "x.b3dm" }
              }
            }
        """.trimIndent()
        val doc = StreamingTilesetReader(body).parseTileset()
        assertTrue(doc.geometricError.isNaN())
        assertEquals(Double.POSITIVE_INFINITY, doc.root.boundingVolume.sphere[3])
        assertEquals(Double.NEGATIVE_INFINITY, doc.root.geometricError)
    }

    @Test
    fun coercesExplicitNullToDefault() {
        // kotlinx behavior we replaced: explicit null for an optional list/object field
        // becomes the field's declared default rather than throwing.
        val body = """
            {
              "asset": { "version": "1.0", "gltfUpAxis": null },
              "geometricError": 0.0,
              "root": {
                "boundingVolume": { "region": [-1, 0, 1, 0.5, 0, 100] },
                "geometricError": 0.0,
                "refine": null,
                "transform": null,
                "content": null,
                "children": null,
                "viewerRequestVolume": null,
                "implicitTiling": null
              }
            }
        """.trimIndent()
        val doc = StreamingTilesetReader(body).parseTileset()
        assertNull(doc.asset.gltfUpAxis)
        assertNull(doc.root.refine)
        assertEquals(0, doc.root.transform.size)
        assertNull(doc.root.content)
        assertEquals(0, doc.root.children.size)
        assertNull(doc.root.viewerRequestVolume)
        assertNull(doc.root.implicitTiling)
    }

    @Test
    fun ignoresUnknownKeys() {
        val body = """
            {
              "asset": { "version": "1.0", "futureField": [1,2,3] },
              "geometricError": 0.0,
              "uniqueExtension": { "deep": { "nested": [true, false, null, "x"] } },
              "root": {
                "boundingVolume": { "region": [0,0,0,0,0,0] },
                "geometricError": 0.0,
                "extras": { "anything": "goes" }
              }
            }
        """.trimIndent()
        val doc = StreamingTilesetReader(body).parseTileset()
        assertEquals("1.0", doc.asset.version)
        assertEquals(6, doc.root.boundingVolume.region.size)
    }

    @Test
    fun parsesEscapedStringContent() {
        val body = """
            {
              "asset": { "version": "1.0" },
              "geometricError": 0.0,
              "root": {
                "boundingVolume": { "region": [0,0,0,0,0,0] },
                "geometricError": 0.0,
                "content": { "uri": "path with \"quotes\" and \\slash\\and\nnewlines.b3dm" }
              }
            }
        """.trimIndent()
        val doc = StreamingTilesetReader(body).parseTileset()
        val uri = doc.root.content?.uri
        assertNotNull(uri)
        assertTrue(uri.contains("\""))
        assertTrue(uri.contains("\\"))
        assertTrue(uri.contains("\n"))
    }

    @Test
    fun parsesImplicitTiling() {
        val body = """
            {
              "asset": { "version": "1.1" },
              "geometricError": 0.0,
              "root": {
                "boundingVolume": { "region": [0,0,0,0,0,0] },
                "geometricError": 0.0,
                "implicitTiling": {
                  "subdivisionScheme": "OCTREE",
                  "subtreeLevels": 5,
                  "availableLevels": 12,
                  "subtrees": { "uri": "subtrees/{level}/{x}/{y}/{z}.subtree" }
                }
              }
            }
        """.trimIndent()
        val doc = StreamingTilesetReader(body).parseTileset()
        val it = doc.root.implicitTiling
        assertNotNull(it)
        assertEquals(SubdivisionScheme.OCTREE, it.subdivisionScheme)
        assertEquals(5, it.subtreeLevels)
        assertEquals(12, it.availableLevels)
        assertEquals("subtrees/{level}/{x}/{y}/{z}.subtree", it.subtrees.uri)
    }

    @Test
    fun acceptsLegacyContentUrlField() {
        // 3D Tiles 0.0 used `url` instead of `uri` on content.
        val body = """
            {
              "asset": { "version": "0.0" },
              "geometricError": 0.0,
              "root": {
                "boundingVolume": { "region": [0,0,0,0,0,0] },
                "geometricError": 0.0,
                "content": { "url": "legacy.b3dm" }
              }
            }
        """.trimIndent()
        val doc = StreamingTilesetReader(body).parseTileset()
        assertNull(doc.root.content?.uri)
        assertEquals("legacy.b3dm", doc.root.content?.urlLegacy)
    }
}
