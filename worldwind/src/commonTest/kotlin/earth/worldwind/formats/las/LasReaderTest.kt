package earth.worldwind.formats.las

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Portable coverage for [LasHeader] / [LasReader] on a hand-built uncompressed LAS 1.2, point
 * format 2 (with 16-bit RGB). Exercises field offsets, scale/offset application, and the
 * 16-bit→8-bit colour detection without needing binary fixtures.
 */
class LasReaderTest {
    @Test
    fun readsFormat2PositionsAndColors() = runTest {
        val bytes = buildFormat2Las()
        val header = LasHeader.parse(bytes)
        assertEquals(2, header.pointDataFormat)
        assertFalse(header.isCompressed)
        assertEquals(2L, header.pointCount)

        val cloud = LasReader.parse(bytes)
        assertEquals(2, cloud.pointCount)
        // scale 0.01, offset 0 → raw 100 = 1.0 m, etc.
        assertEquals(doubleArrayOf(1.0, 2.0, 3.0, 5.0, 6.0, 7.0).toList(), cloud.positions.toList())
        // 16-bit channels (65535, 0, 32768) detected and shifted to 8-bit (255, 0, 128), alpha 255.
        assertEquals(listOf(255, 0, 128, 255), cloud.colors.slice(0..3).map { it.toInt() and 0xFF })
        assertEquals(listOf(0, 255, 0, 255), cloud.colors.slice(4..7).map { it.toInt() and 0xFF })
        assertTrue(cloud.crs is LasCrs.Absent)
    }

    private fun buildFormat2Las(): ByteArray {
        val headerSize = 227
        val recordLen = 26
        val pointCount = 2
        val bytes = ByteArray(headerSize + recordLen * pointCount)
        // signature
        "LASF".forEachIndexed { i, c -> bytes[i] = c.code.toByte() }
        bytes[24] = 1; bytes[25] = 2 // version 1.2
        setU16(bytes, 94, headerSize)
        setU32(bytes, 96, headerSize) // offset to point data
        setU32(bytes, 100, 0) // num VLRs
        bytes[104] = 2 // point format 2
        setU16(bytes, 105, recordLen)
        setU32(bytes, 107, pointCount) // legacy count
        setF64(bytes, 131, 0.01); setF64(bytes, 139, 0.01); setF64(bytes, 147, 0.01) // scale
        setF64(bytes, 155, 0.0); setF64(bytes, 163, 0.0); setF64(bytes, 171, 0.0) // offset

        writePoint(bytes, headerSize, 100, 200, 300, intArrayOf(65535, 0, 32768))
        writePoint(bytes, headerSize + recordLen, 500, 600, 700, intArrayOf(0, 65535, 0))
        return bytes
    }

    private fun writePoint(b: ByteArray, off: Int, x: Int, y: Int, z: Int, rgb: IntArray) {
        setU32(b, off, x); setU32(b, off + 4, y); setU32(b, off + 8, z)
        setU16(b, off + 12, 1000) // intensity
        setU16(b, off + 20, rgb[0]); setU16(b, off + 22, rgb[1]); setU16(b, off + 24, rgb[2])
    }

    private fun setU16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
    private fun setU32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
    }
    private fun setF64(b: ByteArray, o: Int, v: Double) {
        val bits = v.toRawBits()
        for (i in 0 until 8) b[o + i] = (bits shr (8 * i)).toByte()
    }
}
