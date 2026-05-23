package earth.worldwind.formats.shapefile

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [Shapefile] that build the .shp byte buffer programmatically. Avoids
 * needing checked-in binary fixtures; the format spec is small enough to round-trip
 * here. Covers POINT, POLYLINE, POLYGON, and POINT_Z record types.
 */
class ShapefileTest {

    @Test
    fun parsesPointShapefile() {
        val points = listOf(
            doubleArrayOf(-122.4194, 37.7749), // San Francisco
            doubleArrayOf(2.3522, 48.8566),    // Paris
            doubleArrayOf(139.6917, 35.6895),  // Tokyo
        )
        val bytes = buildShapefile(SHP_POINT, pointRecords = points)

        val shapefile = Shapefile(bytes)

        assertEquals(ShapefileShapeType.POINT, shapefile.shapeType)
        assertEquals(3, shapefile.records.size)
        for ((i, record) in shapefile.records.withIndex()) {
            assertEquals(i + 1, record.recordNumber)
            assertTrue(record.isPointType)
            assertEquals(1, record.numberOfParts)
            assertEquals(1, record.numberOfPoints)
            val part = record.parts[0]
            assertEquals(points[i][0], part[0], 1e-9)
            assertEquals(points[i][1], part[1], 1e-9)
        }
    }

    @Test
    fun parsesPolylineShapefile() {
        // A two-part polyline: first part 3 points, second part 2 points.
        val parts = listOf(
            doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0),
            doubleArrayOf(20.0, 20.0, 30.0, 30.0),
        )
        val bytes = buildShapefile(SHP_POLYLINE, polyRecords = listOf(parts))

        val shapefile = Shapefile(bytes)

        assertEquals(ShapefileShapeType.POLYLINE, shapefile.shapeType)
        assertEquals(1, shapefile.records.size)
        val record = shapefile.records[0]
        assertEquals(2, record.numberOfParts)
        assertEquals(5, record.numberOfPoints)
        assertContentEquals(parts[0], record.parts[0])
        assertContentEquals(parts[1], record.parts[1])
        val bbox = record.boundingRectangle!!
        // bbox order is [minY, maxY, minX, maxX]
        assertEquals(0.0, bbox[0], 1e-9)  // minY
        assertEquals(30.0, bbox[1], 1e-9) // maxY
        assertEquals(0.0, bbox[2], 1e-9)  // minX
        assertEquals(30.0, bbox[3], 1e-9) // maxX
    }

    @Test
    fun parsesPolygonShapefile() {
        // Single closed ring (the shapefile spec requires polygon rings to repeat the first point).
        val ring = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0, 0.0, 0.0)
        val bytes = buildShapefile(SHP_POLYGON, polyRecords = listOf(listOf(ring)))

        val shapefile = Shapefile(bytes)

        assertEquals(ShapefileShapeType.POLYGON, shapefile.shapeType)
        val record = shapefile.records.single()
        assertTrue(record.isPolygonType)
        assertEquals(1, record.numberOfParts)
        assertEquals(5, record.numberOfPoints)
    }

    @Test
    fun parsesPointZShapefile() {
        val xyz = listOf(
            doubleArrayOf(0.0, 0.0, 100.0),
            doubleArrayOf(1.0, 1.0, 200.0),
        )
        val bytes = buildShapefile(SHP_POINT_Z, pointZRecords = xyz)

        val shapefile = Shapefile(bytes)

        assertEquals(ShapefileShapeType.POINT_Z, shapefile.shapeType)
        assertEquals(2, shapefile.records.size)
        for ((i, record) in shapefile.records.withIndex()) {
            assertEquals(xyz[i][2], record.zValues!![0], 1e-9)
        }
    }

    @Test
    fun joinsDbaseAttributesByRecordIndex() {
        val points = listOf(
            doubleArrayOf(0.0, 0.0),
            doubleArrayOf(1.0, 1.0),
        )
        val shp = buildShapefile(SHP_POINT, pointRecords = points)
        val dbf = buildSimpleDbf(listOf("Alpha", "Beta"))

        val shapefile = Shapefile(shp, attributes = DBaseFile(dbf))

        assertEquals("Alpha", shapefile.records[0].attributes["NAME"])
        assertEquals("Beta", shapefile.records[1].attributes["NAME"])
    }

    @Test
    fun nullRecordsAreSkipped() {
        // Build a POINT shapefile where one record is shape-type NULL (allowed by spec).
        val bytes = buildShapefile(SHP_POINT, pointRecords = listOf(doubleArrayOf(0.0, 0.0)), trailingNullRecord = true)
        val shapefile = Shapefile(bytes)
        assertEquals(1, shapefile.records.size, "NULL record must not appear in records list")
    }

    @Test
    fun parsesMultiPatchTriangleStrip() {
        val xy = doubleArrayOf(
            0.0, 0.0,
            1.0, 0.0,
            0.0, 1.0,
            1.0, 1.0,
        )
        val z = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val bytes = buildMultiPatchShapefile(
            parts = listOf(xy),
            partTypeCodes = intArrayOf(0),
            zValues = z,
        )
        val shapefile = Shapefile(bytes)
        assertEquals(ShapefileShapeType.MULTI_PATCH, shapefile.shapeType)
        val record = shapefile.records.single()
        assertTrue(record.isMultiPatchType)
        assertNotNull(record.partTypes)
        assertEquals(MultiPatchPartType.TRIANGLE_STRIP, record.partTypes.single())
        assertEquals(4, record.numberOfPoints)
        assertContentEquals(z, record.zValues)
    }

    @Test
    fun recordsSequenceMatchesEagerRecords() {
        val points = listOf(
            doubleArrayOf(0.0, 0.0),
            doubleArrayOf(1.0, 1.0),
            doubleArrayOf(2.0, 2.0),
            doubleArrayOf(3.0, 3.0),
        )
        val shapefile = Shapefile(buildShapefile(SHP_POINT, pointRecords = points))

        val eager = shapefile.records
        val lazy = shapefile.recordsSequence.toList()
        assertEquals(eager.size, lazy.size)
        for (i in eager.indices) {
            assertEquals(eager[i].recordNumber, lazy[i].recordNumber)
            assertContentEquals(eager[i].parts[0], lazy[i].parts[0])
        }
    }

    @Test
    fun recordsSequenceCanShortCircuit() {
        val points = List(100) { doubleArrayOf(it.toDouble(), it.toDouble()) }
        val shapefile = Shapefile(buildShapefile(SHP_POINT, pointRecords = points))
        val first3 = shapefile.recordsSequence.take(3).toList()
        assertEquals(3, first3.size)
        assertEquals(1, first3[0].recordNumber)
        assertEquals(3, first3[2].recordNumber)
    }

    @Test
    fun unknownFileCodeRejected() {
        val bad = ByteArray(100)
        // file code stays 0 — not the magic 0x270A.
        ByteBuffer.wrap(bad).order(ByteOrder.BIG_ENDIAN).putInt(0, 12345)
        val ex = runCatching { Shapefile(bad) }.exceptionOrNull()
        assertNotNull(ex, "Bogus file code must throw")
        assertTrue(ex.message!!.contains("file code"), "Error message should mention file code")
    }
}

// ---- Builder helpers -----------------------------------------------------------------

private const val SHP_POINT = 1
private const val SHP_POLYLINE = 3
private const val SHP_POLYGON = 5
private const val SHP_POINT_Z = 11
private const val SHP_MULTI_PATCH = 31

/** Lightweight in-memory shapefile builder for tests. Supports a subset of record types. */
private fun buildShapefile(
    shapeType: Int,
    pointRecords: List<DoubleArray> = emptyList(),
    pointZRecords: List<DoubleArray> = emptyList(),
    polyRecords: List<List<DoubleArray>> = emptyList(),
    trailingNullRecord: Boolean = false,
): ByteArray {
    // First, build record contents to know total file size.
    val recordBlocks = mutableListOf<ByteArray>()
    when (shapeType) {
        SHP_POINT -> pointRecords.forEachIndexed { i, xy -> recordBlocks.add(buildPointRecord(i + 1, xy[0], xy[1])) }
        SHP_POINT_Z -> pointZRecords.forEachIndexed { i, xyz -> recordBlocks.add(buildPointZRecord(i + 1, xyz[0], xyz[1], xyz[2])) }
        SHP_POLYLINE, SHP_POLYGON ->
            polyRecords.forEachIndexed { i, parts -> recordBlocks.add(buildPolyRecord(i + 1, shapeType, parts)) }
    }
    if (trailingNullRecord) recordBlocks.add(buildNullRecord(recordBlocks.size + 1))

    val totalBytes = 100 + recordBlocks.sumOf { it.size }
    val buffer = ByteBuffer.allocate(totalBytes)
    // Header
    buffer.order(ByteOrder.BIG_ENDIAN)
    buffer.putInt(0, 0x0000270A) // file code
    buffer.putInt(24, totalBytes / 2) // file length in words
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(28, 1000) // version
    buffer.putInt(32, shapeType)
    // bbox + Z + M ranges stay at 0; that's fine for the tests we run.

    var offset = 100
    for (block in recordBlocks) {
        System.arraycopy(block, 0, buffer.array(), offset, block.size)
        offset += block.size
    }
    return buffer.array()
}

private fun buildPointRecord(recordNumber: Int, x: Double, y: Double): ByteArray {
    val contentBytes = 4 + 8 + 8 // type + x + y = 20 bytes
    val buffer = ByteBuffer.allocate(8 + contentBytes).order(ByteOrder.BIG_ENDIAN)
    buffer.putInt(recordNumber)
    buffer.putInt(contentBytes / 2)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(SHP_POINT)
    buffer.putDouble(x)
    buffer.putDouble(y)
    return buffer.array()
}

private fun buildPointZRecord(recordNumber: Int, x: Double, y: Double, z: Double): ByteArray {
    // Type (4) + X (8) + Y (8) + Z (8) + optional M (8). M is optional; omit.
    val contentBytes = 4 + 8 + 8 + 8
    val buffer = ByteBuffer.allocate(8 + contentBytes).order(ByteOrder.BIG_ENDIAN)
    buffer.putInt(recordNumber)
    buffer.putInt(contentBytes / 2)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(SHP_POINT_Z)
    buffer.putDouble(x)
    buffer.putDouble(y)
    buffer.putDouble(z)
    return buffer.array()
}

private fun buildPolyRecord(recordNumber: Int, shapeType: Int, parts: List<DoubleArray>): ByteArray {
    val numParts = parts.size
    val numPoints = parts.sumOf { it.size / 2 }
    val contentBytes = 4 + // type
        4 * 8 + // bbox (4 doubles)
        4 + 4 + // numParts + numPoints
        4 * numParts + // part offsets
        16 * numPoints // 2 doubles per point

    val (minX, maxX, minY, maxY) = run {
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (part in parts) {
            var i = 0
            while (i + 1 < part.size) {
                if (part[i] < minX) minX = part[i]; if (part[i] > maxX) maxX = part[i]
                if (part[i + 1] < minY) minY = part[i + 1]; if (part[i + 1] > maxY) maxY = part[i + 1]
                i += 2
            }
        }
        QuadDouble(minX, maxX, minY, maxY)
    }

    val buffer = ByteBuffer.allocate(8 + contentBytes).order(ByteOrder.BIG_ENDIAN)
    buffer.putInt(recordNumber)
    buffer.putInt(contentBytes / 2)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(shapeType)
    buffer.putDouble(minX); buffer.putDouble(minY); buffer.putDouble(maxX); buffer.putDouble(maxY)
    buffer.putInt(numParts)
    buffer.putInt(numPoints)
    var offset = 0
    for (part in parts) {
        buffer.putInt(offset)
        offset += part.size / 2
    }
    for (part in parts) for (d in part) buffer.putDouble(d)
    return buffer.array()
}

private fun buildNullRecord(recordNumber: Int): ByteArray {
    val buffer = ByteBuffer.allocate(8 + 4).order(ByteOrder.BIG_ENDIAN)
    buffer.putInt(recordNumber)
    buffer.putInt(2) // content length in 16-bit words (just the type field)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(0) // shape type NULL
    return buffer.array()
}

/** Build a minimal dBASE III file with one column NAME (Character, length 10). */
private fun buildSimpleDbf(names: List<String>): ByteArray {
    val nameFieldLength = 10
    val headerLength = 32 + 32 + 1 // fixed header + 1 field descriptor + terminator
    val recordLength = 1 + nameFieldLength // deletion flag + NAME
    val totalSize = headerLength + names.size * recordLength + 1 // + EOF marker

    val out = ByteArray(totalSize)
    val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    // Fixed header
    buf.put(0, 0x03.toByte())  // dBASE III no memo
    buf.put(1, 125.toByte())   // YY = 2025
    buf.put(2, 1.toByte())     // MM
    buf.put(3, 1.toByte())     // DD
    buf.putInt(4, names.size)
    buf.putShort(8, headerLength.toShort())
    buf.putShort(10, recordLength.toShort())
    // Field descriptor at offset 32
    val nameAscii = "NAME".toByteArray(Charsets.ISO_8859_1)
    System.arraycopy(nameAscii, 0, out, 32, nameAscii.size)
    out[32 + 11] = 'C'.code.toByte()
    out[32 + 16] = nameFieldLength.toByte()
    out[32 + 17] = 0
    // Terminator
    out[64] = 0x0D.toByte()
    // Records
    var pos = headerLength
    for (name in names) {
        out[pos] = 0x20 // ' ' = not deleted
        val bytes = name.toByteArray(Charsets.ISO_8859_1)
        val width = minOf(bytes.size, nameFieldLength)
        System.arraycopy(bytes, 0, out, pos + 1, width)
        // pad remaining bytes with spaces
        for (i in pos + 1 + width until pos + 1 + nameFieldLength) out[i] = 0x20
        pos += recordLength
    }
    out[totalSize - 1] = 0x1A // EOF marker
    return out
}

private data class QuadDouble(val a: Double, val b: Double, val c: Double, val d: Double)

private fun buildMultiPatchShapefile(
    parts: List<DoubleArray>,
    partTypeCodes: IntArray,
    zValues: DoubleArray,
): ByteArray {
    require(parts.size == partTypeCodes.size) { "parts/partTypeCodes length mismatch" }
    val numParts = parts.size
    val numPoints = parts.sumOf { it.size / 2 }
    require(zValues.size == numPoints) { "zValues length must match total point count" }

    val contentBytes =
        4 +
        4 * 8 +
        4 + 4 +
        4 * numParts +
        4 * numParts +
        16 * numPoints +
        2 * 8 +
        8 * numPoints

    var minX = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    for (part in parts) {
        var i = 0
        while (i + 1 < part.size) {
            if (part[i] < minX) minX = part[i]; if (part[i] > maxX) maxX = part[i]
            if (part[i + 1] < minY) minY = part[i + 1]; if (part[i + 1] > maxY) maxY = part[i + 1]
            i += 2
        }
    }
    val zMin = zValues.min(); val zMax = zValues.max()

    val totalBytes = 100 + 8 + contentBytes
    val buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.BIG_ENDIAN)
    buf.putInt(0, 0x0000270A)
    buf.putInt(24, totalBytes / 2)
    buf.order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(28, 1000)
    buf.putInt(32, SHP_MULTI_PATCH)

    val recOff = 100
    buf.order(ByteOrder.BIG_ENDIAN)
    buf.putInt(recOff, 1)
    buf.putInt(recOff + 4, contentBytes / 2)

    buf.order(ByteOrder.LITTLE_ENDIAN)
    var pos = recOff + 8
    buf.putInt(pos, SHP_MULTI_PATCH); pos += 4
    buf.putDouble(pos, minX); pos += 8
    buf.putDouble(pos, minY); pos += 8
    buf.putDouble(pos, maxX); pos += 8
    buf.putDouble(pos, maxY); pos += 8
    buf.putInt(pos, numParts); pos += 4
    buf.putInt(pos, numPoints); pos += 4
    var off = 0
    for (part in parts) {
        buf.putInt(pos, off); pos += 4
        off += part.size / 2
    }
    for (code in partTypeCodes) { buf.putInt(pos, code); pos += 4 }
    for (part in parts) for (d in part) { buf.putDouble(pos, d); pos += 8 }
    buf.putDouble(pos, zMin); pos += 8
    buf.putDouble(pos, zMax); pos += 8
    for (z in zValues) { buf.putDouble(pos, z); pos += 8 }
    return buf.array()
}
