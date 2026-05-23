package earth.worldwind.formats.shapefile

/**
 * Character encodings supported by the dBASE / DBF string decoder. Single-byte codepages
 * are decoded in pure Kotlin via 256-entry lookup tables, so no platform-specific
 * Charset support is required. Multi-byte codepages (GBK, Shift_JIS, etc.) are not
 * supported here — callers using those encodings should resolve them on their own.
 *
 * Resolution order in [DBaseFile]:
 *   1. Explicit [DBaseCharset] passed to the constructor.
 *   2. The `.cpg` sidecar text (if provided).
 *   3. The dBASE header byte 29 (the "language driver" code page byte).
 *   4. Fallback to [Latin1].
 */
sealed class DBaseCharset(val name: String) {
    abstract fun decode(bytes: ByteArray, offset: Int, length: Int): String

    /** UTF-8 — what modern files set in their `.cpg` ("UTF-8"). */
    data object Utf8 : DBaseCharset("UTF-8") {
        override fun decode(bytes: ByteArray, offset: Int, length: Int): String =
            bytes.decodeToString(offset, offset + length)
    }

    /** ISO-8859-1 / Latin-1. Every byte maps 1:1 to its Unicode codepoint. */
    data object Latin1 : DBaseCharset("ISO-8859-1") {
        override fun decode(bytes: ByteArray, offset: Int, length: Int): String =
            buildString(length) {
                for (i in 0 until length) append(Char(bytes[offset + i].toInt() and 0xFF))
            }
    }

    /**
     * Single-byte codepage backed by a 256-entry lookup table mapping each byte value to
     * a Unicode codepoint (`-1` for undefined positions). Only BMP codepoints are
     * supported (good enough for Windows-125x and Cyrillic OEM pages).
     */
    class TableBased(name: String, private val table: IntArray) : DBaseCharset(name) {
        override fun decode(bytes: ByteArray, offset: Int, length: Int): String {
            val sb = StringBuilder(length)
            for (i in 0 until length) {
                val b = bytes[offset + i].toInt() and 0xFF
                val cp = table[b]
                sb.append(if (cp >= 0) Char(cp) else Char(b))
            }
            return sb.toString()
        }
    }

    companion object {
        /** Map a `.cpg` sidecar string (case-insensitive) to a charset. Returns `null`
         *  if the name doesn't match any supported codepage. */
        fun fromCpgText(text: String?): DBaseCharset? {
            val normalized = text?.trim()?.uppercase()?.replace(Regex("[-_]"), "") ?: return null
            return when (normalized) {
                "UTF8", "UTF" -> Utf8
                "ISO88591", "LATIN1", "88591", "ASCII", "ANSI" -> Latin1
                "WINDOWS1252", "CP1252", "1252", "WIN1252" -> Windows1252
                "WINDOWS1251", "CP1251", "1251", "WIN1251" -> Windows1251
                "OEM" -> Latin1 // Conservative — CP850/CP437 vary; Latin-1 is the best safe default.
                else -> null
            }
        }

        /** Map a dBASE language-driver byte (DBF header offset 29) to a charset. */
        fun fromLanguageDriver(code: Int): DBaseCharset? = when (code) {
            0x01, 0x02 -> Latin1 // CP437 / CP850 — Latin-1 is close enough for ASCII subsets.
            0x03, 0x57, 0x58, 0x88 -> Windows1252 // Windows ANSI variants.
            0x64, 0x65, 0x66, 0x67 -> Latin1 // CP852 / CP866 — fall back to Latin-1 (Cyrillic OEM not supported).
            0xC8 -> Windows1252
            0xC9 -> Windows1251 // Russian Windows.
            else -> null
        }

        // ---- Lookup tables ---------------------------------------------------------

        val Windows1252: TableBased by lazy { TableBased("WINDOWS-1252", buildWindows1252Table()) }
        val Windows1251: TableBased by lazy { TableBased("WINDOWS-1251", buildWindows1251Table()) }

        /** Windows-1252 only differs from Latin-1 in the C1 range (0x80-0x9F): Western
         *  typographic characters (smart quotes, em-dash, €) replace the unprintable
         *  control codes. Bytes outside that range map identity. */
        private fun buildWindows1252Table(): IntArray {
            val t = IntArray(256) { it }
            // 0x80-0x9F overrides per Unicode WG2.
            val c1 = intArrayOf(
                0x20AC, -1, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
                0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, -1, 0x017D, -1,
                -1, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
                0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, -1, 0x017E, 0x0178,
            )
            for (i in 0 until 32) t[0x80 + i] = c1[i]
            return t
        }

        /** Windows-1251 (Cyrillic) is identity in 0x00-0x7F (ASCII) and uses a mostly
         *  Cyrillic mapping in 0x80-0xFF. The U+0410..U+044F (А-я) main block lives at
         *  0xC0-0xFF; the C1 / A0 range carries the surrounding glyphs (Ё, ё, ©, etc.). */
        private fun buildWindows1251Table(): IntArray {
            val t = IntArray(256) { it }
            // Table from the Unicode Consortium's CP1251 mapping.
            val high = intArrayOf(
                // 0x80-0x8F
                0x0402, 0x0403, 0x201A, 0x0453, 0x201E, 0x2026, 0x2020, 0x2021,
                0x20AC, 0x2030, 0x0409, 0x2039, 0x040A, 0x040C, 0x040B, 0x040F,
                // 0x90-0x9F
                0x0452, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
                -1, 0x2122, 0x0459, 0x203A, 0x045A, 0x045C, 0x045B, 0x045F,
                // 0xA0-0xAF
                0x00A0, 0x040E, 0x045E, 0x0408, 0x00A4, 0x0490, 0x00A6, 0x00A7,
                0x0401, 0x00A9, 0x0404, 0x00AB, 0x00AC, 0x00AD, 0x00AE, 0x0407,
                // 0xB0-0xBF
                0x00B0, 0x00B1, 0x0406, 0x0456, 0x0491, 0x00B5, 0x00B6, 0x00B7,
                0x0451, 0x2116, 0x0454, 0x00BB, 0x0458, 0x0405, 0x0455, 0x0457,
                // 0xC0-0xCF (А..П)
                0x0410, 0x0411, 0x0412, 0x0413, 0x0414, 0x0415, 0x0416, 0x0417,
                0x0418, 0x0419, 0x041A, 0x041B, 0x041C, 0x041D, 0x041E, 0x041F,
                // 0xD0-0xDF (Р..Я)
                0x0420, 0x0421, 0x0422, 0x0423, 0x0424, 0x0425, 0x0426, 0x0427,
                0x0428, 0x0429, 0x042A, 0x042B, 0x042C, 0x042D, 0x042E, 0x042F,
                // 0xE0-0xEF (а..п)
                0x0430, 0x0431, 0x0432, 0x0433, 0x0434, 0x0435, 0x0436, 0x0437,
                0x0438, 0x0439, 0x043A, 0x043B, 0x043C, 0x043D, 0x043E, 0x043F,
                // 0xF0-0xFF (р..я)
                0x0440, 0x0441, 0x0442, 0x0443, 0x0444, 0x0445, 0x0446, 0x0447,
                0x0448, 0x0449, 0x044A, 0x044B, 0x044C, 0x044D, 0x044E, 0x044F,
            )
            for (i in high.indices) t[0x80 + i] = high[i]
            return t
        }
    }
}
