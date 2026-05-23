package earth.worldwind.formats.nitf

/**
 * Spec enumerations from MIL-STD-2500C (NITF 2.1) / NSIF 1.0. We translate the
 * raw string codes from the file/image headers into type-safe enums so the
 * downstream image reader doesn't pass free strings around. Any unrecognised
 * code becomes `null` at parse time — the segment is kept (callers can inspect
 * the raw code) but pixel decode will refuse to proceed.
 */
enum class NitfFormat(val id: String, val version: String) {
    NITF_02_10("NITF", "02.10"),
    NSIF_01_00("NSIF", "01.00");

    companion object {
        internal fun fromCodes(id: String, version: String): NitfFormat? =
            entries.firstOrNull { it.id == id && it.version == version }
    }
}

/** PVTYPE — image pixel value type. MIL-STD-2500C §5.4.3.4. */
enum class NitfPixelValueType(val code: String) {
    /** Integer (unsigned). */
    INT("INT"),
    /** Bi-level (1-bit). */
    BILEVEL("B"),
    /** Two's-complement signed integer. */
    SIGNED("SI"),
    /** IEEE floating-point. */
    REAL("R"),
    /** Complex (2 × IEEE float, real then imaginary). */
    COMPLEX("C");

    companion object {
        internal fun fromCode(s: String): NitfPixelValueType? = entries.firstOrNull { it.code == s.trim() }
    }
}

/** IREP — image representation. */
enum class NitfImageRepresentation(val code: String) {
    MONO("MONO"),
    RGB("RGB"),
    RGB_LUT("RGB/LUT"),
    MULTI("MULTI"),
    NODISPLY("NODISPLY"),
    NVECTOR("NVECTOR"),
    POLAR("POLAR"),
    VPH("VPH"),
    YCBCR601("YCbCr601"),
    MITM("MITM");

    companion object {
        internal fun fromCode(s: String): NitfImageRepresentation? = entries.firstOrNull { it.code == s.trim() }
    }
}

/** IC — image compression. */
enum class NitfCompression(val code: String, val isCompressed: Boolean, val hasMaskBlock: Boolean) {
    /** Uncompressed, no mask. */
    NC("NC", false, false),
    /** Uncompressed, masked. */
    NM("NM", false, true),
    /** Bi-level (ITU-T T.4 / CCITT FAX 1D). */
    C1("C1", true, false),
    /** JPEG (ISO/IEC 10918-1). */
    C3("C3", true, false),
    /** Vector Quantization — used by RPF (CADRG/CIB). */
    C4("C4", true, false),
    /** JPEG 2000 (ISO/IEC 15444-1) — NPJE / EPJE profiles. */
    C5("C5", true, false),
    /** Reserved / future. */
    C6("C6", true, false),
    /** Reserved / future. */
    C7("C7", true, false),
    /** JPEG 2000 (general). */
    C8("C8", true, false),
    /** Down-sampled JPEG. */
    I1("I1", true, false),
    /** Bi-level + mask. */
    M1("M1", true, true),
    /** JPEG + mask. */
    M3("M3", true, true),
    /** VQ + mask. */
    M4("M4", true, true),
    /** JPEG 2000 + mask. */
    M5("M5", true, true),
    /** JPEG 2000 (general) + mask. */
    M8("M8", true, true);

    companion object {
        internal fun fromCode(s: String): NitfCompression? = entries.firstOrNull { it.code == s.trim() }
    }
}

/** IMODE — pixel/block/band ordering inside a compressed-or-uncompressed image. */
enum class NitfImageMode(val code: Char) {
    /** Band-interleaved by block. */
    BAND_BLOCK('B'),
    /** Band-interleaved by pixel. */
    BAND_PIXEL('P'),
    /** Band-interleaved by row. */
    BAND_ROW('R'),
    /** Band-sequential. */
    BAND_SEQUENTIAL('S');

    companion object {
        internal fun fromCode(s: String): NitfImageMode? {
            val t = s.trim()
            return if (t.length != 1) null else entries.firstOrNull { it.code == t[0] }
        }
    }
}

/** ICORDS — coordinate representation that governs the 60-byte IGEOLO field. */
enum class NitfCoordinateSystem(val code: Char) {
    /** No coordinates (IGEOLO field absent). */
    NONE(' '),
    /** UTM/MGRS — IGEOLO encodes MGRS strings. */
    MGRS('U'),
    /** Geographic — IGEOLO encodes ddmmssXdddmmssY (DMS). */
    GEOGRAPHIC('G'),
    /** UTM Northern Hemisphere — IGEOLO encodes zzeeeeeennnnnnnn (zone + easting + northing). */
    UTM_NORTH('N'),
    /** UTM Southern Hemisphere — same layout as N but in the south. */
    UTM_SOUTH('S'),
    /** Geographic decimal degrees — IGEOLO encodes ±dd.ddd±ddd.ddd. */
    DECIMAL('D');

    companion object {
        internal fun fromCode(c: Char): NitfCoordinateSystem? = entries.firstOrNull { it.code == c }
    }
}
