package earth.worldwind.layer.ogc3d

import earth.worldwind.layer.ogc3d.stream.ContentDispatcher
import earth.worldwind.layer.ogc3d.stream.ContentDispatcher.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

class ContentDispatcherTest {
    @Test fun detectsB3dmMagic() {
        assertEquals(Kind.B3DM, ContentDispatcher.detect("b3dm".encodeToByteArray() + byteArrayOf(1, 0, 0, 0)))
    }

    @Test fun detectsI3dmMagic() {
        assertEquals(Kind.I3DM, ContentDispatcher.detect("i3dm".encodeToByteArray() + byteArrayOf(0)))
    }

    @Test fun detectsPntsMagic() {
        assertEquals(Kind.PNTS, ContentDispatcher.detect("pnts".encodeToByteArray() + byteArrayOf(0)))
    }

    @Test fun detectsCmptMagic() {
        assertEquals(Kind.CMPT, ContentDispatcher.detect("cmpt".encodeToByteArray() + byteArrayOf(0)))
    }

    @Test fun detectsGltfMagic() {
        assertEquals(Kind.GLTF, ContentDispatcher.detect("glTF".encodeToByteArray() + byteArrayOf(0)))
    }

    @Test fun detectsTilesetJsonByKeys() {
        val json = """{"asset":{"version":"1.0"},"geometricError":100.0,"root":{}}""".encodeToByteArray()
        assertEquals(Kind.TILESET_JSON, ContentDispatcher.detect(json))
    }

    @Test fun detectsGltfJsonByKeys() {
        val json = """{"asset":{"version":"2.0"},"scenes":[{}]}""".encodeToByteArray()
        assertEquals(Kind.GLTF_JSON, ContentDispatcher.detect(json))
    }

    @Test fun tolerantOfUtf8Bom() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val json = bom + """{"asset":{"version":"1.0"},"geometricError":0.0,"root":{}}""".encodeToByteArray()
        assertEquals(Kind.TILESET_JSON, ContentDispatcher.detect(json))
    }

    @Test fun unknownForRandomBytes() {
        assertEquals(Kind.UNKNOWN, ContentDispatcher.detect(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)))
    }

    @Test fun unknownForEmpty() {
        assertEquals(Kind.UNKNOWN, ContentDispatcher.detect(ByteArray(0)))
    }
}
