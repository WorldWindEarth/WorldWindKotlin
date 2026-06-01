package earth.worldwind.layer.ogc3d.tileset

import earth.worldwind.layer.ogc3d.auth.NoAuthProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubtreeReaderTest {

    @Test fun tileCountMatchesQuadtreeGeometry() {
        // Level 0..3 = 1 + 4 + 16 + 64 = 85 tiles.
        assertEquals(85, SubtreeReader.tileCountForLevels(SubdivisionScheme.QUADTREE, 4))
        // Level 0..2 octree = 1 + 8 + 64 = 73 tiles.
        assertEquals(73, SubtreeReader.tileCountForLevels(SubdivisionScheme.OCTREE, 3))
    }

    @Test fun childSubtreeCountMatchesBranchingFactor() {
        // 3 levels of quadtree → 4^3 = 64 child subtree roots at the leaf boundary.
        assertEquals(64, SubtreeReader.childSubtreeCount(SubdivisionScheme.QUADTREE, 3))
        // 2 levels of octree → 8^2 = 64.
        assertEquals(64, SubtreeReader.childSubtreeCount(SubdivisionScheme.OCTREE, 2))
    }

    @Test fun parsesConstantAllAvailable() {
        // 2-level quadtree (5 tiles total). All-available shorthand: no bitstream needed,
        // tileAvailability.constant = 1.
        val jsonDoc = """
            {"tileAvailability":{"constant":1},
             "contentAvailability":[{"constant":1}],
             "childSubtreeAvailability":{"constant":0}}
        """.trimIndent()
        val payload = buildSubtreePayload(jsonDoc, ByteArray(0))

        val subtree = SubtreeReader.parse(payload, SubdivisionScheme.QUADTREE, subtreeLevels = 2)
        assertEquals(5, subtree.tileAvailability.size)
        assertTrue(subtree.tileAvailability.all { it })
        assertEquals(1, subtree.contentAvailability.size)
        assertTrue(subtree.contentAvailability[0].all { it })
        assertEquals(16, subtree.childSubtreeAvailability.size) // 4^2 leaves
        assertTrue(subtree.childSubtreeAvailability.none { it })
    }

    @Test fun parsesPackedBitstreamLsbFirst() {
        // 2-level quadtree (5 tiles). Pack pattern 0b10101 across 1 byte (low bits set on
        // even indices 0, 2, 4).
        val bin = byteArrayOf(0b10101.toByte()) // bits: 1 at 0, 2, 4
        val jsonDoc = """
            {"tileAvailability":{"bitstream":0},
             "contentAvailability":[],
             "childSubtreeAvailability":{"constant":0},
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":1}]}
        """.trimIndent()
        val payload = buildSubtreePayload(jsonDoc, bin)

        val subtree = SubtreeReader.parse(payload, SubdivisionScheme.QUADTREE, subtreeLevels = 2)
        assertEquals(5, subtree.tileAvailability.size)
        assertTrue(subtree.tileAvailability[0])
        assertEquals(false, subtree.tileAvailability[1])
        assertTrue(subtree.tileAvailability[2])
        assertEquals(false, subtree.tileAvailability[3])
        assertTrue(subtree.tileAvailability[4])
    }

    @Test fun rejectsBadMagic() {
        val bytes = ByteArray(24).apply {
            this[0] = 'b'.code.toByte(); this[1] = 'a'.code.toByte()
            this[2] = 'd'.code.toByte(); this[3] = '!'.code.toByte()
        }
        assertFailsWith<IllegalArgumentException> {
            SubtreeReader.parse(bytes, SubdivisionScheme.QUADTREE, subtreeLevels = 1)
        }
    }

    @Test fun rejectsTruncatedPayload() {
        // Header claims 64-byte JSON + 0-byte binary but file is only 24 bytes.
        val payload = ByteArray(24).apply {
            this[0] = 's'.code.toByte(); this[1] = 'u'.code.toByte()
            this[2] = 'b'.code.toByte(); this[3] = 't'.code.toByte()
            this[4] = 1 // version LE
            this[8] = 64 // jsonLen lower byte = 64
        }
        assertFailsWith<IllegalArgumentException> {
            SubtreeReader.parse(payload, SubdivisionScheme.QUADTREE, subtreeLevels = 1)
        }
    }

    @Test fun tileWithImplicitTilingDescription() {
        // Verifies the parser-side wiring: a tileset.json whose root tile carries
        // `implicitTiling` should surface that metadata on the resulting Tile3d.
        val tilesetJson = """
            {
              "asset": {"version": "1.1"},
              "geometricError": 100,
              "root": {
                "boundingVolume": {"sphere": [0, 0, 0, 100]},
                "geometricError": 100,
                "refine": "REPLACE",
                "implicitTiling": {
                  "subdivisionScheme": "QUADTREE",
                  "subtreeLevels": 3,
                  "availableLevels": 6,
                  "subtrees": {"uri": "subtrees/{level}/{x}/{y}.subtree"}
                },
                "content": {"uri": "content/{level}/{x}/{y}.b3dm"}
              }
            }
        """.trimIndent()
        val tileset = TilesetParser.parse(
            body = tilesetJson,
            baseUri = "https://example.com/tileset.json",
            authProvider = NoAuthProvider,
        )
        val implicit = tileset.root.implicitTiling
        assertNotNull(implicit)
        assertEquals(SubdivisionScheme.QUADTREE, implicit.subdivisionScheme)
        assertEquals(3, implicit.subtreeLevels)
        assertEquals(6, implicit.availableLevels)
        assertEquals("subtrees/{level}/{x}/{y}.subtree", implicit.subtrees.uri)
    }

    // ---------- helpers ----------

    private fun buildSubtreePayload(jsonDoc: String, binary: ByteArray): ByteArray {
        // Pad JSON to 8-byte alignment (the spec recommends it; SubtreeReader doesn't require
        // alignment, but real-world publishers do pad and we want to exercise the same shape).
        val jsonBytes = jsonDoc.encodeToByteArray()
        val pad = (8 - (jsonBytes.size % 8)) % 8
        val paddedJson = jsonBytes + ByteArray(pad) { ' '.code.toByte() }

        val total = 24 + paddedJson.size + binary.size
        val out = ByteArray(total)
        // Magic 'subt'
        out[0] = 's'.code.toByte()
        out[1] = 'u'.code.toByte()
        out[2] = 'b'.code.toByte()
        out[3] = 't'.code.toByte()
        // Version 1 (LE)
        out[4] = 1
        // JSON byte length (LE uint64) — only low 4 bytes meaningful for our test sizes.
        writeUInt32LE(out, 8, paddedJson.size)
        // Binary byte length (LE uint64).
        writeUInt32LE(out, 16, binary.size)
        paddedJson.copyInto(out, 24)
        binary.copyInto(out, 24 + paddedJson.size)
        return out
    }

    private fun writeUInt32LE(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
