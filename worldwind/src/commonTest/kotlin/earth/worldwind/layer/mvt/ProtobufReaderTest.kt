package earth.worldwind.layer.mvt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProtobufReaderTest {

    @Test fun readsSingleByteVarint() {
        val r = ProtobufReader(byteArrayOf(0x00))
        assertEquals(0L, r.readVarint())
        val r1 = ProtobufReader(byteArrayOf(0x01))
        assertEquals(1L, r1.readVarint())
        val r127 = ProtobufReader(byteArrayOf(0x7F))
        assertEquals(127L, r127.readVarint())
    }

    @Test fun readsMultiByteVarint() {
        // 150 = 0x96 = 10010110₂ → low-7 = 0010110₂ (=22), high bit cont; next byte = 0x01
        val r = ProtobufReader(byteArrayOf(0xAC.toByte(), 0x02))
        assertEquals(300L, r.readVarint())
        // 1 << 28 = 268_435_456 → 5-byte varint
        val r28 = ProtobufReader(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01))
        assertEquals(1L shl 28, r28.readVarint())
    }

    @Test fun zigZagDecode() {
        // sint encoding round-trips: 0→0, -1→1, 1→2, -2→3, 2→4
        val expectedFromRaw = mapOf(0L to 0L, 1L to -1L, 2L to 1L, 3L to -2L, 4L to 2L)
        for ((raw, signed) in expectedFromRaw) {
            // Encode `raw` as a one-byte varint then sint-decode.
            val r = ProtobufReader(byteArrayOf(raw.toByte()))
            assertEquals(signed, r.readSInt(), "raw $raw should zigzag to $signed")
        }
    }

    @Test fun readsString() {
        // length=5, then "hello"
        val bytes = byteArrayOf(5, 'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte())
        val r = ProtobufReader(bytes)
        assertEquals("hello", r.readString())
        assertEquals(bytes.size, r.pos)
    }

    @Test fun readsFloatLittleEndian() {
        // 1.0f = 0x3F800000 in IEEE 754, little-endian on the wire
        val r = ProtobufReader(byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F))
        assertEquals(1.0f, r.readFloat())
    }

    @Test fun readsDoubleLittleEndian() {
        // 1.0 = 0x3FF0000000000000
        val r = ProtobufReader(byteArrayOf(0, 0, 0, 0, 0, 0, 0xF0.toByte(), 0x3F))
        assertEquals(1.0, r.readDouble())
    }

    @Test fun delimitedLimitMatchesPosition() {
        // length=3 followed by 3 payload bytes
        val r = ProtobufReader(byteArrayOf(3, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()))
        val limit = r.readDelimitedLimit()
        assertEquals(4, limit)         // payload ends at offset 4
        assertEquals(1, r.pos)         // cursor sits at first payload byte
    }

    @Test fun skipValuesAdvancesByWireType() {
        // varint field (tag (1<<3)|0 = 0x08), value 42
        val r1 = ProtobufReader(byteArrayOf(0x08, 42))
        val t1 = r1.readVarint().toInt()
        assertEquals(0, t1 and 7)
        r1.skipValue(t1)
        assertEquals(2, r1.pos)

        // length-delimited (tag (2<<3)|2 = 0x12), length=2, payload 2 bytes
        val r2 = ProtobufReader(byteArrayOf(0x12, 2, 0, 0))
        val t2 = r2.readVarint().toInt()
        assertEquals(2, t2 and 7)
        r2.skipValue(t2)
        assertEquals(4, r2.pos)

        // I32 (tag (3<<3)|5 = 0x1D), payload 4 bytes
        val r3 = ProtobufReader(byteArrayOf(0x1D, 0, 0, 0, 0))
        val t3 = r3.readVarint().toInt()
        assertEquals(5, t3 and 7)
        r3.skipValue(t3)
        assertEquals(5, r3.pos)

        // I64 (tag (4<<3)|1 = 0x21), payload 8 bytes
        val r4 = ProtobufReader(byteArrayOf(0x21, 0, 0, 0, 0, 0, 0, 0, 0))
        val t4 = r4.readVarint().toInt()
        assertEquals(1, t4 and 7)
        r4.skipValue(t4)
        assertEquals(9, r4.pos)
    }

    @Test fun truncatedVarintThrows() {
        // 0x80 has the continuation bit set but no next byte
        val r = ProtobufReader(byteArrayOf(0x80.toByte()))
        val ex = assertFailsWith<IllegalStateException> { r.readVarint() }
        assertTrue(ex.message!!.contains("truncated"))
    }
}
