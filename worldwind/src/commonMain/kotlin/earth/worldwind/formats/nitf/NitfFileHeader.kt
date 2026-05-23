package earth.worldwind.formats.nitf

/**
 * NITF 2.1 / NSIF 1.0 file header (the very first segment of every file). The
 * parser walks the spec-defined positional layout (MIL-STD-2500C §5.2.1) and
 * captures every spec field, but only a few are exposed as typed accessors —
 * the rest are stashed in [securityBlock] / [unparsed] for applications that
 * care.
 *
 * The two non-header outputs that downstream code actually needs are:
 *  - the segment offset/length tables ([imageSegments], [graphicSegments],
 *    [textSegments], [dataExtensionSegments], [reservedExtensionSegments]).
 *  - the file/header lengths so callers can decide whether the byte buffer is
 *    a complete file.
 */
class NitfFileHeader internal constructor(
    /** Format + version pair (NITF 02.10 or NSIF 01.00). */
    val format: NitfFormat,
    /** CLEVEL — file complexity level (3–9 per spec). */
    val complexityLevel: Int,
    /** STYPE — standard type ("BF01" per spec). */
    val standardType: String,
    /** OSTAID — originating station ID. */
    val originatingStationId: String,
    /** FDT — file date/time, CCYYMMDDhhmmss. */
    val fileDateTime: String,
    /** FTITLE — free-text title. */
    val title: String,
    /** Total file length in bytes (FL). May be 999…9 to indicate "not set"
     *  — spec §5.2.1.1; we surface the raw value and let callers decide. */
    val fileLength: Long,
    /** Header length in bytes (HL) — used to seek past the file header. */
    val headerLength: Int,
    /** FBKGC — file background colour (R,G,B) in 0..255, NITF 2.1 only. */
    val backgroundColor: IntArray?,
    /** ONAME / OPHONE. */
    val originatorName: String,
    val originatorPhone: String,
    /** ENCRYP — must be "0" in NITF 2.1; we surface for inspection. */
    val encryption: Int,
    /** Per-image (offset, length) pairs. Offsets are absolute file positions
     *  pointing at the IM header byte. Length = LISH + LI. */
    val imageSegments: List<SegmentLocation>,
    val graphicSegments: List<SegmentLocation>,
    val textSegments: List<SegmentLocation>,
    val dataExtensionSegments: List<SegmentLocation>,
    val reservedExtensionSegments: List<SegmentLocation>,
    /** Raw UDHD bytes (User-Defined Header Data), or null if UDHDL == 0. */
    val userDefinedHeader: ByteArray?,
    /** Raw XHD bytes (Extended Header Data), or null if XHDL == 0. */
    val extendedHeader: ByteArray?,
) {

    /** Position + size of a segment inside the file. [headerLength] is the
     *  subheader (LISH/LSSH/LTSH/LDSH/LRESH), [dataLength] is the segment data
     *  (LI/LS/LT/LD/LRE). */
    data class SegmentLocation(
        /** Absolute byte offset of the segment's subheader (first byte). */
        val headerOffset: Int,
        /** Length of the subheader in bytes. */
        val headerLength: Int,
        /** Length of the segment data in bytes. */
        val dataLength: Long,
    ) {
        /** Absolute byte offset of the segment's data section (just past the subheader). */
        val dataOffset: Long get() = headerOffset.toLong() + headerLength
        /** Total bytes consumed by this segment (subheader + data). */
        val totalLength: Long get() = headerLength.toLong() + dataLength
    }

    companion object {
        internal fun parse(reader: NitfReader): NitfFileHeader {
            reader.seek(0)

            val fhdr = reader.readString(4)
            val fver = reader.readString(5)
            val format = NitfFormat.fromCodes(fhdr, fver)
                ?: error("Unsupported NITF format: '$fhdr' '$fver' — expected NITF 02.10 or NSIF 01.00")

            val clevel = reader.readInt(2)
            val stype = reader.readString(4)
            val ostaid = reader.readString(10)
            val fdt = reader.readString(14)
            val ftitle = reader.readString(80)

            // Security field block — NITF 2.1 layout. 167 bytes total.
            val securityBytes = reader.readBytes(167)
            // FSCOP / FSCPYS (file copy number / total). Spec §5.2.1.5.
            reader.readString(5)
            reader.readString(5)
            val encryp = reader.readInt(1)

            // FBKGC: three binary bytes (NOT BCS — spec §5.2.1.6).
            val fbkgc = IntArray(3) { reader.readUByte() }

            val oname = reader.readString(24)
            val ophone = reader.readString(18)

            val fl = reader.readLong(12)
            val hl = reader.readInt(6)

            val images = readSegmentTable(reader, lengthSubheader = 6, lengthData = 10)
            val graphics = readSegmentTable(reader, lengthSubheader = 4, lengthData = 6)
            // NUMX is reserved (always 000) per spec §5.2.1.10, but it does
            // consume 3 bytes — read and discard.
            reader.readString(3)
            val texts = readSegmentTable(reader, lengthSubheader = 4, lengthData = 5)
            val des = readSegmentTable(reader, lengthSubheader = 4, lengthData = 9)
            val res = readSegmentTable(reader, lengthSubheader = 4, lengthData = 7)

            // UDHD / XHD: each is (5-byte length, 3-byte overflow ref, N-byte payload).
            val udhdl = reader.readInt(5)
            val udhd = if (udhdl > 0) {
                reader.readString(3) // UDHOFL
                reader.readBytes(udhdl - 3)
            } else null
            val xhdl = reader.readInt(5)
            val xhd = if (xhdl > 0) {
                reader.readString(3) // XHDLOFL
                reader.readBytes(xhdl - 3)
            } else null

            // Pin down per-segment absolute offsets now that we know HL.
            val imageLocs = resolveSegmentOffsets(images, startOffset = hl.toLong())
            var cursor = imageLocs.lastOrNull()?.let { it.dataOffset + it.dataLength }
                ?: hl.toLong()
            val graphicLocs = resolveSegmentOffsets(graphics, startOffset = cursor)
            cursor = graphicLocs.lastOrNull()?.let { it.dataOffset + it.dataLength } ?: cursor
            val textLocs = resolveSegmentOffsets(texts, startOffset = cursor)
            cursor = textLocs.lastOrNull()?.let { it.dataOffset + it.dataLength } ?: cursor
            val desLocs = resolveSegmentOffsets(des, startOffset = cursor)
            cursor = desLocs.lastOrNull()?.let { it.dataOffset + it.dataLength } ?: cursor
            val resLocs = resolveSegmentOffsets(res, startOffset = cursor)

            return NitfFileHeader(
                format = format,
                complexityLevel = clevel,
                standardType = stype,
                originatingStationId = ostaid,
                fileDateTime = fdt,
                title = ftitle,
                fileLength = fl,
                headerLength = hl,
                backgroundColor = fbkgc,
                originatorName = oname,
                originatorPhone = ophone,
                encryption = encryp,
                imageSegments = imageLocs,
                graphicSegments = graphicLocs,
                textSegments = textLocs,
                dataExtensionSegments = desLocs,
                reservedExtensionSegments = resLocs,
                userDefinedHeader = udhd,
                extendedHeader = xhd,
            ).also { it.securityBlock = securityBytes }
        }

        private fun readSegmentTable(
            reader: NitfReader,
            lengthSubheader: Int,
            lengthData: Int,
        ): List<Pair<Int, Long>> {
            val n = reader.readInt(3)
            if (n == 0) return emptyList()
            val out = ArrayList<Pair<Int, Long>>(n)
            repeat(n) {
                val lsub = reader.readInt(lengthSubheader)
                val ldata = reader.readLong(lengthData)
                out += lsub to ldata
            }
            return out
        }

        private fun resolveSegmentOffsets(
            tables: List<Pair<Int, Long>>,
            startOffset: Long,
        ): List<SegmentLocation> {
            if (tables.isEmpty()) return emptyList()
            val out = ArrayList<SegmentLocation>(tables.size)
            var cursor = startOffset
            for ((sub, data) in tables) {
                out += SegmentLocation(
                    headerOffset = cursor.toInt(),
                    headerLength = sub,
                    dataLength = data,
                )
                cursor += sub + data
            }
            return out
        }
    }

    /** Raw 167-byte security block (CLAS/CLSY/CODE/CTLH/REL/DCTP/DCDT/DCXM/DG/
     *  DGDT/CLTX/CATP/CAUT/CRSN/SRDT/CTLN concatenated). Kept around for callers
     *  that need to inspect downgrade/release tags. */
    var securityBlock: ByteArray? = null
        internal set

    /** Tags consumed by future TRE parsers but currently uninterpreted. */
    val unparsed: MutableMap<String, ByteArray> = mutableMapOf()
}
