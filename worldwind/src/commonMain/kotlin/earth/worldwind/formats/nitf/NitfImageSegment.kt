package earth.worldwind.formats.nitf

import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector

/**
 * Parsed subheader for one NITF/NSIF image segment. The image *data* (the
 * pixel grid itself, possibly preceded by a block mask) is not decoded here —
 * see [NitfImageReader.decodeArgb] / [NitfImageReader.decodeRaw].
 *
 * Fields follow MIL-STD-2500C §5.4 and use the spec acronyms in the doc
 * comments so the source is easy to cross-reference with the standard.
 */
class NitfImageSegment internal constructor(
    /** Absolute byte offset of this segment's subheader inside the file. */
    val headerOffset: Int,
    /** Length of the subheader in bytes (matches LISH). */
    val headerLength: Int,
    /** Absolute byte offset of the image data section (just past the subheader). */
    val dataOffset: Long,
    /** Length of the image data section in bytes (matches LI). */
    val dataLength: Long,

    /** IID1 — image identification 1. */
    val imageId: String,
    /** IDATIM — image date/time (CCYYMMDDhhmmss). */
    val dateTime: String,
    /** TGTID — target identifier. */
    val targetId: String,
    /** IID2 — image identification 2 (a longer free-text title). */
    val imageTitle: String,

    /** ENCRYP — must be 0 in NITF 2.1. */
    val encryption: Int,
    /** ISORCE — image source. */
    val imageSource: String,

    /** NROWS — number of significant rows in the image. */
    val numRows: Long,
    /** NCOLS — number of significant columns in the image. */
    val numCols: Long,

    /** PVTYPE — pixel value type. */
    val pixelValueType: NitfPixelValueType,
    /** IREP — image representation. */
    val imageRepresentation: NitfImageRepresentation,
    /** ICAT — image category, kept as raw 8-char code (VIS, SL, TI, MAP, …). */
    val imageCategory: String,
    /** ABPP — actual bits per pixel (1..96). */
    val actualBitsPerPixel: Int,
    /** PJUST — 'L' (left) or 'R' (right) justification for sub-byte sizes. */
    val pixelJustification: Char,

    /** ICORDS — coordinate system selector. */
    val coordinateSystem: NitfCoordinateSystem,
    /** Raw 60-byte IGEOLO field if present, or null. */
    val igeolo: String?,
    /** Decoded corners (NW, NE, SE, SW) when [coordinateSystem] is decodable. */
    val corners: List<Location>?,
    /** Axis-aligned bounding box derived from [corners], or null. */
    val sector: Sector?,

    /** NICOM + ICOM records. */
    val imageComments: List<String>,

    /** IC — compression code. */
    val compression: NitfCompression,
    /** COMRAT — compression rate code (e.g. "C8C5" for some J2K profiles). null if uncompressed. */
    val compressionRate: String?,

    /** NBANDS / XBANDS resolved to a single integer band count. */
    val numBands: Int,
    /** Per-band information (length == [numBands]). */
    val bands: List<Band>,

    /** ISYNC — image sync code (always 0 per spec). */
    val syncCode: Int,
    /** IMODE — pixel/band/block ordering. */
    val imageMode: NitfImageMode,
    /** NBPR — number of blocks per row of the image grid. */
    val blocksPerRow: Int,
    /** NBPC — number of blocks per column of the image grid. */
    val blocksPerCol: Int,
    /** NPPBH — number of pixels per block, horizontal. */
    val pixelsPerBlockH: Int,
    /** NPPBV — number of pixels per block, vertical. */
    val pixelsPerBlockV: Int,
    /** NBPP — number of bits per pixel per band (storage size; ≥ ABPP). */
    val bitsPerPixel: Int,
    /** IDLVL — display level. */
    val displayLevel: Int,
    /** IALVL — attachment level. */
    val attachmentLevel: Int,
    /** ILOC — image location offset (row, col). */
    val imageLocation: IntArray,
    /** IMAG — magnification. */
    val magnification: String,

    /** Raw UDID bytes if UDIDL > 0. */
    val userDefinedImageData: ByteArray?,
    /** Raw IXSHD bytes if IXSHDL > 0. */
    val extendedImageSubheader: ByteArray?,
) {

    /** Per-band metadata. */
    class Band internal constructor(
        /** IREPBANDn — band-specific representation tag (R/G/B/M/etc.). */
        val representation: String,
        /** ISUBCATn — band-specific subcategory. */
        val subCategory: String,
        /** IFCn — image filter condition (placeholder 'N' in current spec). */
        val filterCondition: Char,
        /** IMFLTn — image filter code (reserved). */
        val filterCode: String,
        /** Decoded LUT, or null if NLUTSn == 0. Shape: `[nLuts][nEntries]` of 0..255. */
        val lookupTables: Array<IntArray>?,
    )

    /** Total uncompressed bytes per band (rounded up to a byte) ignoring masking. */
    val bytesPerSampleUncompressed: Int get() = (bitsPerPixel + 7) / 8

    /** Sample alignment for uncompressed reads — same as [bytesPerSampleUncompressed]. */
    val bytesPerPixelUncompressed: Int get() = bytesPerSampleUncompressed * numBands

    companion object {
        internal fun parse(reader: NitfReader, headerOffset: Int, headerLength: Int, dataLength: Long): NitfImageSegment {
            reader.seek(headerOffset)

            val im = reader.readString(2)
            check(im == "IM") { "Image segment header does not start with 'IM' (saw '$im')" }

            val iid1 = reader.readString(10)
            val idatim = reader.readString(14)
            val tgtid = reader.readString(17)
            val iid2 = reader.readString(80)

            // Security block (image-segment flavour, NITF 2.1 layout — 167 bytes).
            reader.skip(167)

            val encryp = reader.readInt(1)
            val isorce = reader.readString(42)

            val nrows = reader.readLong(8)
            val ncols = reader.readLong(8)

            val pvtype = NitfPixelValueType.fromCode(reader.readString(3))
                ?: error("Unknown PVTYPE")
            val irep = NitfImageRepresentation.fromCode(reader.readString(8))
                ?: error("Unknown IREP")
            val icat = reader.readString(8)
            val abpp = reader.readInt(2)
            val pjust = reader.readString(1).firstOrNull() ?: 'R'
            val icordsChar = reader.readBytes(1)[0].toInt().toChar()
            val coordinateSystem = NitfCoordinateSystem.fromCode(icordsChar)
                ?: error("Unknown ICORDS '$icordsChar'")

            val igeolo: String?
            val corners: List<Location>?
            val sector: Sector?
            if (coordinateSystem == NitfCoordinateSystem.NONE) {
                igeolo = null; corners = null; sector = null
            } else {
                igeolo = reader.readString(60)
                NitfCoordinates.decode(coordinateSystem, igeolo).let {
                    corners = it?.corners
                    sector = it?.sector
                }
            }

            val nicom = reader.readInt(1)
            val comments = (0 until nicom).map { reader.readString(80) }

            val ic = NitfCompression.fromCode(reader.readString(2))
                ?: error("Unknown IC")
            val comrat = if (ic == NitfCompression.NC || ic == NitfCompression.NM) {
                null
            } else {
                reader.readString(4)
            }

            var nbands = reader.readInt(1)
            if (nbands == 0) nbands = reader.readInt(5)
            val bands = (0 until nbands).map { readBand(reader) }

            val isync = reader.readInt(1)
            val imode = NitfImageMode.fromCode(reader.readString(1))
                ?: error("Unknown IMODE")
            val nbpr = reader.readInt(4)
            val nbpc = reader.readInt(4)
            val nppbh = reader.readInt(4)
            val nppbv = reader.readInt(4)
            val nbpp = reader.readInt(2)
            val idlvl = reader.readInt(3)
            val ialvl = reader.readInt(3)

            // ILOC: two 5-char signed offsets. Spec allows '-0001' style.
            val ilocRow = reader.readString(5).trim().toInt()
            val ilocCol = reader.readString(5).trim().toInt()
            val imag = reader.readString(4)

            val udidl = reader.readInt(5)
            val udid = if (udidl > 0) {
                reader.readString(3) // UDOFL
                reader.readBytes(udidl - 3)
            } else null

            val ixshdl = reader.readInt(5)
            val ixshd = if (ixshdl > 0) {
                reader.readString(3) // IXSOFL
                reader.readBytes(ixshdl - 3)
            } else null

            return NitfImageSegment(
                headerOffset = headerOffset,
                headerLength = headerLength,
                dataOffset = headerOffset.toLong() + headerLength,
                dataLength = dataLength,
                imageId = iid1,
                dateTime = idatim,
                targetId = tgtid,
                imageTitle = iid2,
                encryption = encryp,
                imageSource = isorce,
                numRows = nrows,
                numCols = ncols,
                pixelValueType = pvtype,
                imageRepresentation = irep,
                imageCategory = icat,
                actualBitsPerPixel = abpp,
                pixelJustification = pjust,
                coordinateSystem = coordinateSystem,
                igeolo = igeolo,
                corners = corners,
                sector = sector,
                imageComments = comments,
                compression = ic,
                compressionRate = comrat,
                numBands = nbands,
                bands = bands,
                syncCode = isync,
                imageMode = imode,
                blocksPerRow = nbpr,
                blocksPerCol = nbpc,
                pixelsPerBlockH = nppbh,
                pixelsPerBlockV = nppbv,
                bitsPerPixel = nbpp,
                displayLevel = idlvl,
                attachmentLevel = ialvl,
                imageLocation = intArrayOf(ilocRow, ilocCol),
                magnification = imag,
                userDefinedImageData = udid,
                extendedImageSubheader = ixshd,
            )
        }

        private fun readBand(reader: NitfReader): Band {
            val irepband = reader.readString(2)
            val isubcat = reader.readString(6)
            val ifc = reader.readString(1).firstOrNull() ?: ' '
            val imflt = reader.readString(3)
            val nluts = reader.readInt(1)
            val luts: Array<IntArray>?
            if (nluts > 0) {
                val nelut = reader.readInt(5)
                luts = Array(nluts) {
                    val raw = reader.readBytes(nelut)
                    IntArray(nelut) { i -> raw[i].toInt() and 0xFF }
                }
            } else {
                luts = null
            }
            return Band(irepband, isubcat, ifc, imflt, luts)
        }
    }
}
