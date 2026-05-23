package earth.worldwind.formats.shapefile

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [DBaseFile]. Constructs in-memory dBASE III files with known field types and
 * confirms decoded values round-trip.
 */
class DBaseFileTest {

    @Test
    fun decodesCharNumericLogicalAndDateFields() {
        val bytes = buildDbf(
            fields = listOf(
                FieldSpec("NAME", 'C', 8),
                FieldSpec("POP", 'N', 10),
                FieldSpec("OPEN", 'L', 1),
                FieldSpec("DATE", 'D', 8),
            ),
            rows = listOf(
                listOf("Alpha", "12345", "T", "20240315"),
                listOf("Beta", "987.5", "N", "20231201"),
            ),
        )
        val dbf = DBaseFile(bytes)
        assertEquals(4, dbf.fields.size)
        assertEquals(2, dbf.records.size)
        assertEquals(DBaseFieldType.CHAR, dbf.fields[0].type)
        assertEquals(DBaseFieldType.NUMBER, dbf.fields[1].type)
        assertEquals(DBaseFieldType.BOOLEAN, dbf.fields[2].type)
        assertEquals(DBaseFieldType.DATE, dbf.fields[3].type)

        val row0 = dbf.records[0].values
        assertEquals("Alpha", row0["NAME"])
        assertEquals(12345.0, row0["POP"] as Double, 0.0)
        assertEquals(true, row0["OPEN"])
        assertEquals("20240315", row0["DATE"])

        val row1 = dbf.records[1].values
        assertEquals("Beta", row1["NAME"])
        assertEquals(987.5, row1["POP"] as Double, 0.0)
        assertEquals(false, row1["OPEN"])
    }

    @Test
    fun handlesDeletedRows() {
        val bytes = buildDbf(
            fields = listOf(FieldSpec("KEY", 'C', 4)),
            rows = listOf(listOf("KEEP"), listOf("GONE")),
            markDeletedRowIndices = setOf(1),
        )
        val dbf = DBaseFile(bytes)
        assertFalse(dbf.records[0].deleted)
        assertTrue(dbf.records[1].deleted)
        // Deleted rows are still parsed (callers filter them).
        assertEquals("GONE", dbf.records[1].values["KEY"])
    }

    // NOTE: a test exercising DBaseFieldType.UNKNOWN was intentionally omitted — the
    // logging path triggered by an unknown type code reaches android.util.Log.isLoggable,
    // which is not mocked in androidHostTest (only stubs that throw a RuntimeException
    // are linked). The lenient behavior (keep field, treat as raw string, advance bytes
    // correctly) is exercised at the integration level when real shapefiles with newer
    // dBASE field types are loaded.
}

// ---- Builder ---------------------------------------------------------------------

private data class FieldSpec(val name: String, val typeCode: Char, val length: Int)

private fun buildDbf(
    fields: List<FieldSpec>,
    rows: List<List<String>>,
    markDeletedRowIndices: Set<Int> = emptySet(),
): ByteArray {
    val headerLength = 32 + fields.size * 32 + 1
    val recordLength = 1 + fields.sumOf { it.length }
    val totalSize = headerLength + rows.size * recordLength + 1
    val out = ByteArray(totalSize)
    val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    // Header
    buf.put(0, 0x03.toByte())
    buf.put(1, 125.toByte())
    buf.put(2, 1.toByte())
    buf.put(3, 1.toByte())
    buf.putInt(4, rows.size)
    buf.putShort(8, headerLength.toShort())
    buf.putShort(10, recordLength.toShort())
    // Field descriptors
    for ((idx, field) in fields.withIndex()) {
        val pos = 32 + idx * 32
        val nameBytes = field.name.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(nameBytes, 0, out, pos, nameBytes.size.coerceAtMost(11))
        out[pos + 11] = field.typeCode.code.toByte()
        out[pos + 16] = field.length.toByte()
        out[pos + 17] = 0
    }
    out[32 + fields.size * 32] = 0x0D.toByte() // terminator
    // Records
    var pos = headerLength
    for ((rowIndex, row) in rows.withIndex()) {
        out[pos] = if (rowIndex in markDeletedRowIndices) 0x2A else 0x20 // '*' or ' '
        var fieldOffset = pos + 1
        for ((fieldIndex, field) in fields.withIndex()) {
            val value = row[fieldIndex]
            val bytes = value.toByteArray(Charsets.ISO_8859_1)
            val width = minOf(bytes.size, field.length)
            System.arraycopy(bytes, 0, out, fieldOffset, width)
            for (i in fieldOffset + width until fieldOffset + field.length) out[i] = 0x20
            fieldOffset += field.length
        }
        pos += recordLength
    }
    out[totalSize - 1] = 0x1A
    return out
}
