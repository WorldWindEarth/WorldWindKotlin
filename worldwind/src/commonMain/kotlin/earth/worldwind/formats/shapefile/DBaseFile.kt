package earth.worldwind.formats.shapefile

import earth.worldwind.formats.BinaryDataView
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.WARN

/**
 * Parser for a dBASE III / IV (.dbf) sidecar file holding shapefile attributes. Records
 * are read eagerly into [records]; row N (one-based, matching the .shp record number) is
 * at [records]`[N - 1]`.
 *
 * Mirrors WebWorldWind's `DBaseFile` + `DBaseField` + `DBaseRecord` triplet.
 *
 * Field types supported: `C` (character), `N` (number), `F` (float — also returned as
 * number), `L` (logical), `D` (date string `YYYYMMDD`). Unknown type bytes cause the
 * parser to drop the field (with a warning) rather than fail.
 */
class DBaseFile(bytes: ByteArray) {
    /**
     * Date stored in the dBASE header. Months are 1-based here (the on-disk byte is also
     * 1-based; we keep that interpretation).
     */
    data class ModificationDate(val year: Int, val month: Int, val day: Int)

    val fileCode: Int
    val lastModificationDate: ModificationDate
    val numberOfRecords: Int
    val headerLength: Int
    val recordLength: Int
    val fields: List<DBaseField>
    val records: List<DBaseRecord>

    init {
        val view = BinaryDataView(bytes)
        if (view.byteLength < FIXED_HEADER_LENGTH) {
            error("DBase header is truncated (need $FIXED_HEADER_LENGTH bytes, have ${view.byteLength})")
        }

        fileCode = view.getUint8(0)
        // WebWW accepts file codes 0..5 only. Newer dBASE / FoxPro variants use 0x03, 0x83
        // for memo, etc. — be slightly more lenient by warning instead of throwing.
        if (fileCode and 0x07 > 5) {
            Logger.log(WARN, "Unrecognized DBase file code: 0x${fileCode.toString(16)}")
        }
        val yy = view.getUint8(1)
        val mm = view.getUint8(2)
        val dd = view.getUint8(3)
        lastModificationDate = ModificationDate(year = 1900 + yy, month = mm, day = dd)
        numberOfRecords = view.getInt32(4, littleEndian = true)
        headerLength = view.getUint16(8, littleEndian = true)
        recordLength = view.getUint16(10, littleEndian = true)

        // ---- Field descriptors -----------------------------------------------------
        // headerLength includes the fixed 32-byte header + N×32-byte field descriptors +
        // a 1-byte 0x0D terminator. WebWW uses (headerLength - 1 - 32) / 32 as the count.
        val numFields = ((headerLength - 1 - FIXED_HEADER_LENGTH) / FIELD_DESCRIPTOR_LENGTH).coerceAtLeast(0)
        val parsedFields = mutableListOf<DBaseField>()
        var pos = FIXED_HEADER_LENGTH
        for (i in 0 until numFields) {
            if (pos + FIELD_DESCRIPTOR_LENGTH > view.byteLength) break
            val name = readNullTerminatedString(view, pos, FIELD_NAME_LENGTH).trim()
            val typeByte = view.getUint8(pos + 11)
            val typeChar = Char(typeByte)
            // bytes 12..15 are a deprecated field data address — ignored.
            val length = view.getUint8(pos + 16)
            val decimals = view.getUint8(pos + 17)
            val type = DBaseFieldType.fromCode(typeChar)
            if (type == null) {
                // Unknown type code (memo, integer, currency, double, etc. from newer dBASE
                // variants). Keep the field in the schema as UNKNOWN so the per-row reader
                // still advances `length` bytes — otherwise subsequent fields would read
                // from the wrong offset.
                Logger.log(WARN, "DBase field '$name' has unsupported type '$typeChar' — bytes preserved as raw string")
                parsedFields.add(DBaseField(name = name, type = DBaseFieldType.UNKNOWN, typeCode = typeChar, length = length, decimals = decimals))
            } else {
                parsedFields.add(DBaseField(name = name, type = type, typeCode = typeChar, length = length, decimals = decimals))
            }
            pos += FIELD_DESCRIPTOR_LENGTH
        }
        fields = parsedFields

        // ---- Records ---------------------------------------------------------------
        val rows = mutableListOf<DBaseRecord>()
        // Records start immediately after the header. Each is recordLength bytes,
        // beginning with a 1-byte deletion flag (' ' = not deleted, '*' = deleted).
        var recordOffset = headerLength
        for (i in 1..numberOfRecords) {
            if (recordOffset + recordLength > view.byteLength) {
                Logger.log(WARN, "DBase record $i extends past file end; truncating")
                break
            }
            val deleted = view.getUint8(recordOffset) == 0x2A // '*'
            val values = LinkedHashMap<String, Any?>(parsedFields.size)
            var fieldOffset = recordOffset + 1
            for (field in parsedFields) {
                val raw = readNullTerminatedString(view, fieldOffset, field.length).trim()
                values[field.name] = decodeFieldValue(field, raw)
                fieldOffset += field.length
            }
            rows.add(DBaseRecord(recordNumber = i, deleted = deleted, values = values))
            recordOffset += recordLength
        }
        records = rows
    }

    private fun decodeFieldValue(field: DBaseField, raw: String): Any? {
        if (raw.isEmpty()) return when (field.type) {
            DBaseFieldType.CHAR -> ""
            DBaseFieldType.UNKNOWN -> ""
            else -> null
        }
        return when (field.type) {
            DBaseFieldType.CHAR -> raw
            DBaseFieldType.UNKNOWN -> raw
            DBaseFieldType.BOOLEAN -> when (raw.first().lowercaseChar()) {
                't', 'y' -> true
                'f', 'n' -> false
                else -> null
            }
            DBaseFieldType.NUMBER -> raw.toDoubleOrNull()
            DBaseFieldType.DATE -> raw // keep as `YYYYMMDD` string; cross-platform Date parsing isn't worth it here
        }
    }

    /**
     * Reads up to [maxLength] bytes starting at [offset], stopping at NUL. Bytes are
     * mapped Latin-1 / ISO-8859-1 style (the same charset semantics WebWorldWind's
     * `String.fromCharCode(byte)` produces).
     */
    private fun readNullTerminatedString(view: BinaryDataView, offset: Int, maxLength: Int): String {
        if (maxLength <= 0) return ""
        val sb = StringBuilder(maxLength)
        for (i in 0 until maxLength) {
            val b = view.getUint8(offset + i)
            if (b == 0) break
            sb.append(Char(b))
        }
        val s = sb.toString()
        // Treat space-fill ('  …  ') and asterisk-fill ('***') as logically empty.
        return if (isLogicallyEmpty(s)) "" else s
    }

    private fun isLogicallyEmpty(s: String): Boolean {
        if (s.isEmpty()) return true
        val first = s[0]
        if (first != ' ' && first != '*') return false
        for (c in s) if (c != first) return false
        return true
    }

    companion object {
        const val FIXED_HEADER_LENGTH: Int = 32
        const val FIELD_DESCRIPTOR_LENGTH: Int = 32
        const val FIELD_NAME_LENGTH: Int = 11
    }
}

/**
 * Schema for a single DBF column.
 *
 * @property name Column name.
 * @property type Decoded type category.
 * @property typeCode Raw single-character type code from the field descriptor.
 * @property length On-disk width in bytes of every value of this field.
 * @property decimals Decimal-place count (numeric fields).
 */
data class DBaseField(
    val name: String,
    val type: DBaseFieldType,
    val typeCode: Char,
    val length: Int,
    val decimals: Int,
)

enum class DBaseFieldType(val code: Char) {
    CHAR('C'),
    NUMBER('N'),
    DATE('D'),
    BOOLEAN('L'),
    /** Field type that we don't decode (memo, integer, currency, etc.). Values are
     *  exposed as the raw byte run interpreted as Latin-1, so callers can still extract
     *  the data if they know the encoding. */
    UNKNOWN('?');

    companion object {
        fun fromCode(code: Char): DBaseFieldType? = when (code) {
            'C' -> CHAR
            'N', 'F' -> NUMBER // FoxPro float — same semantics as N for our purposes
            'D' -> DATE
            'L' -> BOOLEAN
            else -> null
        }
    }
}

/**
 * One row of attributes. [recordNumber] is one-based and matches the .shp record number.
 * [deleted] is the dBASE record deletion flag; deleted rows are still parsed and exposed
 * so callers can filter them.
 */
data class DBaseRecord(
    val recordNumber: Int,
    val deleted: Boolean,
    val values: Map<String, Any?>,
)
