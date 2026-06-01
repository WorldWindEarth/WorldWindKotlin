package earth.worldwind.layer.ogc3d

import earth.worldwind.formats.gltf.DracoDecoder
import earth.worldwind.formats.gltf.DracoMesh
import earth.worldwind.formats.gltf.GltfReader
import earth.worldwind.formats.gltf.GltfDecoderRegistry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DracoIntegrationTest {

    private var savedDecoder: DracoDecoder? = null

    @BeforeTest fun saveRegistry() {
        savedDecoder = GltfDecoderRegistry.dracoDecoder
        GltfDecoderRegistry.dracoDecoder = null
    }

    @AfterTest fun restoreRegistry() {
        GltfDecoderRegistry.dracoDecoder = savedDecoder
    }

    /**
     * Minimal glTF JSON whose single primitive uses KHR_draco_mesh_compression. No fallback
     * accessors — the primitive should be silently skipped when no decoder is registered.
     */
    private val dracoOnlyGltf = """
        {
          "scenes": [{"nodes": [0]}],
          "nodes": [{"mesh": 0}],
          "meshes": [{"primitives": [{
              "extensions": {
                "KHR_draco_mesh_compression": {
                  "bufferView": 0,
                  "attributes": {"POSITION": 0, "NORMAL": 1}
                }
              }
          }]}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 16}],
          "buffers": [{"byteLength": 16}],
          "accessors": []
        }
    """.trimIndent()

    private val binChunk: ByteArray = ByteArray(16) { it.toByte() }

    @Test fun primitiveSkippedWhenNoDecoderRegistered() {
        val model = GltfReader.parse(dracoOnlyGltf, binChunk)
        assertEquals(1, model.meshes.size)
        // The single Draco-only primitive should drop out because POSITION accessor < 0 and
        // no decoder is available.
        assertEquals(0, model.meshes[0].primitives.size)
    }

    @Test fun decoderCalledAndOutputUsed() {
        var decodeCount = 0
        GltfDecoderRegistry.dracoDecoder = object : DracoDecoder {
            override fun decode(compressedBuffer: ByteArray, attributeIds: Map<String, Int>): DracoMesh {
                decodeCount++
                // Sanity-check what the reader handed us. Buffer should be the entire 16-byte
                // chunk; attributes map should match the JSON.
                assertEquals(16, compressedBuffer.size)
                assertEquals(0, attributeIds["POSITION"])
                assertEquals(1, attributeIds["NORMAL"])
                return DracoMesh(
                    positions = floatArrayOf(0f, 0f, 0f,  1f, 0f, 0f,  0f, 1f, 0f),
                    normals = floatArrayOf(0f, 0f, 1f,  0f, 0f, 1f,  0f, 0f, 1f),
                )
            }
        }
        val model = GltfReader.parse(dracoOnlyGltf, binChunk)
        assertEquals(1, decodeCount, "Decoder should be invoked exactly once")
        assertEquals(1, model.meshes[0].primitives.size)
        val prim = model.meshes[0].primitives[0]
        assertEquals(9, prim.positions.size)
        assertTrue(prim.normals != null && prim.normals.size == 9)
    }

    @Test fun decoderFailureSkipsPrimitiveButContinues() {
        GltfDecoderRegistry.dracoDecoder = object : DracoDecoder {
            override fun decode(compressedBuffer: ByteArray, attributeIds: Map<String, Int>): DracoMesh {
                error("simulated codec failure")
            }
        }
        val model = GltfReader.parse(dracoOnlyGltf, binChunk)
        // Decoder threw, so the primitive is skipped. The mesh still parses.
        assertEquals(1, model.meshes.size)
        assertEquals(0, model.meshes[0].primitives.size)
    }
}
