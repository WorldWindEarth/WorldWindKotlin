package earth.worldwind.layer.ogc3d

import earth.worldwind.formats.gltf.GltfReader
import earth.worldwind.formats.gltf.GltfDecoderRegistry
import earth.worldwind.formats.gltf.Ktx2Decoder
import earth.worldwind.formats.gltf.Ktx2Image
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Ktx2IntegrationTest {

    private var savedDecoder: Ktx2Decoder? = null

    @BeforeTest fun saveRegistry() {
        savedDecoder = GltfDecoderRegistry.ktx2Decoder
        GltfDecoderRegistry.ktx2Decoder = null
    }

    @AfterTest fun restoreRegistry() {
        GltfDecoderRegistry.ktx2Decoder = savedDecoder
    }

    /**
     * Minimal glTF JSON whose single texture sources a KTX2 image via KHR_texture_basisu.
     * The "image" bufferView is non-empty so the basisu path is actually exercised; the
     * sentinel bytes never reach a real decoder unless the test installs one.
     */
    private val basisuOnlyGltf = """
        {
          "scenes": [{"nodes": [0]}],
          "nodes": [{"mesh": 0}],
          "meshes": [{"primitives": [{
              "attributes": {"POSITION": 0},
              "material": 0
          }]}],
          "materials": [{
              "pbrMetallicRoughness": {
                  "baseColorTexture": {"index": 0}
              }
          }],
          "textures": [{
              "sampler": 0,
              "source": -1,
              "extensions": {
                  "KHR_texture_basisu": {"source": 0}
              }
          }],
          "images": [{"bufferView": 0, "mimeType": "image/ktx2"}],
          "samplers": [{}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 32}],
          "buffers": [{"byteLength": 32}],
          "accessors": [{"bufferView": 0, "componentType": 5126, "count": 0, "type": "VEC3"}]
        }
    """.trimIndent()

    private val binChunk: ByteArray = ByteArray(32) { it.toByte() }

    @Test fun imageEmittedEmptyWhenNoDecoderRegistered() {
        val model = GltfReader.parse(basisuOnlyGltf, binChunk)
        assertEquals(1, model.images.size)
        // No decoder, no transcoded RGBA — and no PNG/JPG fallback either, so the image
        // stays empty and downstream code skips the texture.
        assertEquals(0, model.images[0].bytes.size)
        assertEquals(null, model.images[0].decodedRgba)
    }

    @Test fun decoderCalledAndOutputUsed() {
        var decodeCount = 0
        GltfDecoderRegistry.ktx2Decoder = object : Ktx2Decoder {
            override fun decode(ktx2Bytes: ByteArray): Ktx2Image {
                decodeCount++
                assertEquals(32, ktx2Bytes.size, "bufferView slice should match declared byteLength")
                return Ktx2Image(rgba = ByteArray(4 * 4 * 4), width = 4, height = 4)
            }
        }
        val model = GltfReader.parse(basisuOnlyGltf, binChunk)
        assertEquals(1, decodeCount, "Decoder should be invoked exactly once")
        val img = model.images[0]
        assertTrue(img.decodedRgba != null && img.decodedRgba.size == 4 * 4 * 4)
        assertEquals(4, img.decodedWidth)
        assertEquals(4, img.decodedHeight)
        assertEquals(0, img.bytes.size, "Raw bytes should be cleared once transcoding succeeds")
    }

    @Test fun decoderFailureLeavesImageEmpty() {
        GltfDecoderRegistry.ktx2Decoder = object : Ktx2Decoder {
            override fun decode(ktx2Bytes: ByteArray): Ktx2Image = error("simulated codec failure")
        }
        val model = GltfReader.parse(basisuOnlyGltf, binChunk)
        // Decoder threw, so the texture is omitted. The model still parses; the primitive
        // renders untextured but doesn't crash the whole tile.
        assertEquals(1, model.images.size)
        assertEquals(0, model.images[0].bytes.size)
        assertEquals(null, model.images[0].decodedRgba)
    }

    @Test fun basisuExtensionOverridesPlainSourceWhenBothPresent() {
        // glTF spec: KHR_texture_basisu.source supersedes the texture's regular source
        // when the extension is present and a decoder can transcode it. The material
        // therefore points at the basisu image index (1) instead of the fallback (0).
        val withFallback = """
            {
              "scenes": [{"nodes": [0]}],
              "nodes": [{"mesh": 0}],
              "meshes": [{"primitives": [{
                  "attributes": {"POSITION": 0},
                  "material": 0
              }]}],
              "materials": [{
                  "pbrMetallicRoughness": {"baseColorTexture": {"index": 0}}
              }],
              "textures": [{
                  "sampler": 0,
                  "source": 0,
                  "extensions": {"KHR_texture_basisu": {"source": 1}}
              }],
              "images": [
                  {"uri": "data:image/png;base64,iVBORw0KGgo="},
                  {"bufferView": 0, "mimeType": "image/ktx2"}
              ],
              "samplers": [{}],
              "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 32}],
              "buffers": [{"byteLength": 32}],
              "accessors": [{"bufferView": 0, "componentType": 5126, "count": 0, "type": "VEC3"}]
            }
        """.trimIndent()
        GltfDecoderRegistry.ktx2Decoder = object : Ktx2Decoder {
            override fun decode(ktx2Bytes: ByteArray): Ktx2Image =
                Ktx2Image(rgba = ByteArray(16), width = 2, height = 2)
        }
        val model = GltfReader.parse(withFallback, binChunk)
        val material = model.materials.first()
        assertEquals(1, material.baseColorTextureImageIndex, "Should resolve to the basisu source (1), not the PNG fallback (0)")
    }
}
