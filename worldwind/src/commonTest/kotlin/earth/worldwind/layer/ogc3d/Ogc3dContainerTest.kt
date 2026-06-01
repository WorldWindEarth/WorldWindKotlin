package earth.worldwind.layer.ogc3d

import earth.worldwind.formats.ogc3d.CmptLoader
import earth.worldwind.formats.ogc3d.Ogc3dHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Ogc3dContainerTest {
    /**
     * Build a minimal b3dm payload: 28-byte header + empty feature/batch sections + a GLB
     * stub. The stub doesn't need to be a valid GLB for this test — we're just checking
     * the header decode end-to-end and the payload-offset math.
     */
    private fun buildB3dm(
        ftJsonLen: Int = 0,
        ftBinLen: Int = 0,
        btJsonLen: Int = 0,
        btBinLen: Int = 0,
        glbStub: ByteArray = ByteArray(0),
    ): ByteArray {
        val payload = ftJsonLen + ftBinLen + btJsonLen + btBinLen + glbStub.size
        val total = 28 + payload
        val out = ByteArray(total)
        // magic
        "b3dm".encodeToByteArray().copyInto(out, 0)
        // version = 1
        out[4] = 1
        // byteLength = total (LE)
        writeLE32(out, 8, total)
        writeLE32(out, 12, ftJsonLen)
        writeLE32(out, 16, ftBinLen)
        writeLE32(out, 20, btJsonLen)
        writeLE32(out, 24, btBinLen)
        glbStub.copyInto(out, 28 + ftJsonLen + ftBinLen + btJsonLen + btBinLen)
        return out
    }

    private fun writeLE32(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    @Test fun parsesMinimalB3dmHeader() {
        val bytes = buildB3dm(glbStub = ByteArray(100))
        val header = Ogc3dHeader.parse(bytes)
        assertEquals("b3dm", header.magic)
        assertEquals(1, header.version)
        assertEquals(128, header.byteLength)
        assertEquals(0, header.featureTableJsonByteLength)
        assertEquals(28, header.payloadOffset)
    }

    @Test fun computesPayloadOffsetAcrossSubLengths() {
        val bytes = buildB3dm(ftJsonLen = 16, ftBinLen = 8, btJsonLen = 32, btBinLen = 4, glbStub = ByteArray(50))
        val header = Ogc3dHeader.parse(bytes)
        assertEquals(28 + 16 + 8 + 32 + 4, header.payloadOffset)
        assertEquals(28, header.featureTableJsonOffset)
        assertEquals(44, header.featureTableBinaryOffset)
        assertEquals(52, header.batchTableJsonOffset)
        assertEquals(84, header.batchTableBinaryOffset)
    }

    @Test fun rejectsBadMagic() {
        val bytes = buildB3dm()
        bytes[0] = 'x'.code.toByte()
        assertFailsWith<IllegalArgumentException> { Ogc3dHeader.parse(bytes) }
    }

    @Test fun rejectsBadVersion() {
        val bytes = buildB3dm()
        bytes[4] = 2  // version = 2 instead of 1
        assertFailsWith<IllegalArgumentException> { Ogc3dHeader.parse(bytes) }
    }

    @Test fun rejectsTruncatedHeader() {
        val bytes = ByteArray(10)  // smaller than 28
        assertFailsWith<IllegalArgumentException> { Ogc3dHeader.parse(bytes) }
    }

    @Test fun splitsCmptIntoInnerTiles() {
        // Build a cmpt with two minimal b3dm inner tiles, each 28 bytes of pure header.
        val inner1 = buildB3dm()
        val inner2 = buildB3dm()
        val total = 16 + inner1.size + inner2.size
        val out = ByteArray(total)
        "cmpt".encodeToByteArray().copyInto(out, 0)
        out[4] = 1
        writeLE32(out, 8, total)
        writeLE32(out, 12, 2)  // tilesLength
        inner1.copyInto(out, 16)
        inner2.copyInto(out, 16 + inner1.size)

        val tiles = CmptLoader.parse(out)
        assertEquals(2, tiles.size)
        assertEquals("b3dm", tiles[0].magic)
        assertEquals("b3dm", tiles[1].magic)
        assertEquals(28, tiles[0].bytes.size)
        assertEquals(28, tiles[1].bytes.size)
    }
}
