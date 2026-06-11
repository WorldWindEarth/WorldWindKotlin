package earth.worldwind.layer.ogc3d

import earth.worldwind.formats.gltf.GltfDecoderRegistry
import earth.worldwind.formats.gltf.GltfReader
import earth.worldwind.formats.gltf.MeshoptDecoder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MeshoptIntegrationTest {

    private var savedDecoder: MeshoptDecoder? = null

    @BeforeTest fun saveRegistry() {
        savedDecoder = GltfDecoderRegistry.meshoptDecoder
        GltfDecoderRegistry.meshoptDecoder = null
    }

    @AfterTest fun restoreRegistry() {
        GltfDecoderRegistry.meshoptDecoder = savedDecoder
    }

    /**
     * Minimal glTF where one bufferView is tagged with EXT_meshopt_compression. The
     * primitive's POSITION accessor reads from that bufferView, so GltfReader should call
     * the registered decoder, splice the decoded bytes back into a new buffer entry, and
     * the position read should pull from the decoded data.
     */
    private val meshoptGltf = """
        {
          "scenes": [{"nodes": [0]}],
          "nodes": [{"mesh": 0}],
          "meshes": [{"primitives": [{
              "attributes": {"POSITION": 0}
          }]}],
          "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"}
          ],
          "bufferViews": [
            {
              "buffer": 0,
              "byteOffset": 0,
              "byteLength": 36,
              "byteStride": 12,
              "extensions": {
                "EXT_meshopt_compression": {
                  "buffer": 0,
                  "byteOffset": 0,
                  "byteLength": 8,
                  "byteStride": 12,
                  "count": 3,
                  "mode": "ATTRIBUTES"
                }
              }
            }
          ],
          "buffers": [{"byteLength": 8}]
        }
    """.trimIndent()

    /** 8 bytes of "compressed" data — the test fixture decoder ignores it and emits a
     *  fixed 36-byte payload (three vec3 floats). */
    private val binChunk: ByteArray = ByteArray(8) { it.toByte() }

    /** Three vec3 positions: (1,0,0), (0,1,0), (0,0,1). */
    private val expectedRgba: ByteArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    ).let { floats ->
        // Pack as little-endian float32 bytes — accessor reader uses BinaryDataView.getFloat32(le=true).
        ByteArray(floats.size * 4) { i ->
            val floatIdx = i / 4
            val byteIdx = i % 4
            val bits = floats[floatIdx].toRawBits()
            (bits shr (byteIdx * 8) and 0xFF).toByte()
        }
    }

    @Test fun bufferViewSkippedWhenNoDecoderRegistered() {
        val model = GltfReader.parse(meshoptGltf, binChunk)
        // No decoder → bufferView length forced to 0; accessor reads short-circuit and
        // the primitive ends up with no position attribute. Per parser convention,
        // primitives with positions.size < 3 are skipped.
        assertEquals(1, model.meshes.size)
        assertEquals(0, model.meshes[0].primitives.size)
    }

    @Test fun decoderInvokedAndOutputPushedIntoAccessor() {
        var decodeCount = 0
        GltfDecoderRegistry.meshoptDecoder = object : MeshoptDecoder {
            override fun decode(
                compressed: ByteArray,
                count: Int,
                byteStride: Int,
                mode: MeshoptDecoder.Mode,
                filter: MeshoptDecoder.Filter?,
            ): ByteArray {
                decodeCount++
                assertEquals(8, compressed.size)
                assertEquals(3, count)
                assertEquals(12, byteStride)
                assertEquals(MeshoptDecoder.Mode.ATTRIBUTES, mode)
                return expectedRgba
            }
        }
        val model = GltfReader.parse(meshoptGltf, binChunk)
        assertEquals(1, decodeCount, "Decoder should be invoked exactly once for the meshopt bufferView")
        assertEquals(1, model.meshes[0].primitives.size)
        val prim = model.meshes[0].primitives[0]
        assertEquals(9, prim.positions.size, "Three vec3 positions decoded")
        assertNotNull(prim.positions)
        // Spot-check the three positions came through unchanged.
        assertEquals(1f, prim.positions[0])
        assertEquals(0f, prim.positions[1])
        assertEquals(0f, prim.positions[2])
        assertEquals(0f, prim.positions[3])
        assertEquals(1f, prim.positions[4])
        assertEquals(0f, prim.positions[5])
        assertEquals(0f, prim.positions[6])
        assertEquals(0f, prim.positions[7])
        assertEquals(1f, prim.positions[8])
    }

    @Test fun decoderFailureDropsAttribute() {
        GltfDecoderRegistry.meshoptDecoder = object : MeshoptDecoder {
            override fun decode(
                compressed: ByteArray, count: Int, byteStride: Int,
                mode: MeshoptDecoder.Mode, filter: MeshoptDecoder.Filter?,
            ): ByteArray = error("simulated decoder failure")
        }
        val model = GltfReader.parse(meshoptGltf, binChunk)
        // Decoder threw → bufferView length forced to 0 → POSITION absent → primitive skipped.
        assertEquals(0, model.meshes[0].primitives.size)
    }
}
