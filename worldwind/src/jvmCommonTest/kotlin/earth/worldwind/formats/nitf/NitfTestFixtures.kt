package earth.worldwind.formats.nitf

import java.util.Locale

/**
 * Hand-synthesises a minimal NITF 2.1 file in memory for tests — no real .ntf
 * fixture needed. The parser code we're testing is itself the only "ground
 * truth", so the fixtures bias toward exercising layouts that are easy to get
 * wrong (multi-block, multi-band, multi-row-block IMODE permutations,
 * NM-masked blocks) rather than recapitulating the entire spec.
 *
 * All numeric fields are spec-formatted (zero-padded, right-justified, ASCII).
 */

/** Builds a synthetic NITF file with one image segment. */
internal class NitfFixture(
    val width: Int = 8,
    val height: Int = 8,
    val blocksPerRow: Int = 1,
    val blocksPerCol: Int = 1,
    val pixelsPerBlockH: Int = width,
    val pixelsPerBlockV: Int = height,
    val nbpp: Int = 8,
    val nbands: Int = 1,
    val imode: Char = 'B',
    val irep: String = "MONO",
    val icords: Char = 'D',
    val igeolo: String = formatDecimalIgeolo(45.0, 46.0, -120.0, -119.0),
    val masked: Boolean = false,
    val transparentCode: Int? = null,
    val maskedBlocks: Set<Int> = emptySet(),
    val pixel: (band: Int, row: Int, col: Int) -> Int = { _, r, c -> (r * 8 + c) and 0xFF },
)

internal fun synthNitf(fx: NitfFixture): ByteArray {
    val bytesPerSample = (fx.nbpp + 7) / 8
    val pixelsPerBlock = fx.pixelsPerBlockH * fx.pixelsPerBlockV
    val bytesPerBlock = pixelsPerBlock * bytesPerSample * (if (fx.imode == 'B' || fx.imode == 'S') 1 else fx.nbands)
    val totalBlocks = fx.blocksPerRow * fx.blocksPerCol
    val maskEntries = when (fx.imode) {
        'B' -> totalBlocks * fx.nbands
        'P', 'R' -> totalBlocks
        'S' -> totalBlocks * fx.nbands
        else -> totalBlocks
    }
    // For IC=NM, missing blocks are absent from the data stream — only the
    // BMR marks them. So the on-disk byte count excludes them.
    val presentBlocks = if (fx.masked) maskEntries - fx.maskedBlocks.size else maskEntries
    val imageDataBytes = presentBlocks * bytesPerBlock

    val maskHeaderBytes = if (fx.masked) {
        4 /* IMDATOFF */ + 2 /* BMRLNTH */ + 2 /* TMRLNTH */ + 2 /* TPXCDLNTH */ +
            (if (fx.transparentCode != null) (fx.nbpp + 7) / 8 else 0) +
            (maskEntries * 4)
    } else 0

    val lish = computeSubheaderLength(fx)
    val li = (maskHeaderBytes + imageDataBytes).toLong()
    val hl = 388 + 16
    val fl = hl + lish + li

    val out = ByteArray(fl.toInt())
    var p = 0

    fun writeAscii(s: String, len: Int) {
        val padded = s.padEnd(len, ' ').substring(0, len)
        for (i in 0 until len) out[p + i] = padded[i].code.toByte()
        p += len
    }
    fun writeAsciiRJ(s: String, len: Int, padChar: Char = '0') {
        val final = if (s.length >= len) s.substring(s.length - len) else s.padStart(len, padChar)
        for (i in 0 until len) out[p + i] = final[i].code.toByte()
        p += len
    }
    fun writeBytes(b: ByteArray) {
        b.copyInto(out, p, 0, b.size)
        p += b.size
    }

    // --- File header ---
    writeAscii("NITF", 4)
    writeAscii("02.10", 5)
    writeAsciiRJ("3", 2)                    // CLEVEL
    writeAscii("BF01", 4)                   // STYPE
    writeAscii("TESTSTATN", 10)             // OSTAID
    writeAscii("20260523000000", 14)        // FDT
    writeAscii("World Wind NITF test", 80)  // FTITLE
    // Security block — leave all-blank (unclassified).
    p += 167
    writeAsciiRJ("0", 5)                    // FSCOP
    writeAsciiRJ("0", 5)                    // FSCPYS
    writeAscii("0", 1)                      // ENCRYP
    p += 3                                  // FBKGC (binary 0,0,0 — already zero)
    writeAscii("ONAME", 24)
    writeAscii("OPHONE", 18)
    writeAsciiRJ(fl.toString(), 12)
    writeAsciiRJ(hl.toString(), 6)
    writeAsciiRJ("1", 3)                    // NUMI
    writeAsciiRJ(lish.toString(), 6)        // LISH
    writeAsciiRJ(li.toString(), 10)         // LI
    writeAsciiRJ("0", 3)                    // NUMS
    writeAsciiRJ("0", 3)                    // NUMX (reserved)
    writeAsciiRJ("0", 3)                    // NUMT
    writeAsciiRJ("0", 3)                    // NUMDES
    writeAsciiRJ("0", 3)                    // NUMRES
    writeAsciiRJ("0", 5)                    // UDHDL
    writeAsciiRJ("0", 5)                    // XHDL
    check(p == hl) { "File header expected $hl bytes, wrote $p" }

    // --- Image subheader ---
    val subStart = p
    writeAscii("IM", 2)
    writeAscii("MISSION001", 10)            // IID1
    writeAscii("20260523000000", 14)        // IDATIM
    writeAscii("TARGET ID PADDED ", 17)     // TGTID
    writeAscii("Synthetic", 80)             // IID2
    p += 167                                // Security (blank)
    writeAscii("0", 1)                      // ENCRYP
    writeAscii("synthetic", 42)             // ISORCE
    writeAsciiRJ(fx.height.toString(), 8)   // NROWS
    writeAsciiRJ(fx.width.toString(), 8)    // NCOLS
    writeAscii("INT", 3)                    // PVTYPE
    writeAscii(fx.irep, 8)                  // IREP
    writeAscii("VIS", 8)                    // ICAT
    writeAsciiRJ(fx.nbpp.toString(), 2)     // ABPP
    writeAscii("R", 1)                      // PJUST
    writeAscii(fx.icords.toString(), 1)     // ICORDS
    if (fx.icords != ' ') writeAscii(fx.igeolo, 60)
    writeAsciiRJ("0", 1)                    // NICOM
    writeAscii(if (fx.masked) "NM" else "NC", 2)
    writeAsciiRJ(fx.nbands.toString(), 1)   // NBANDS
    // Per-band info (no LUT).
    for (b in 0 until fx.nbands) {
        val rep = when (fx.irep) {
            "RGB" -> when (b) { 0 -> "R "; 1 -> "G "; 2 -> "B "; else -> "  " }
            "MONO" -> "M "
            else -> "  "
        }
        writeAscii(rep, 2)                  // IREPBAND
        writeAscii("VIS", 6)                // ISUBCAT
        writeAscii("N", 1)                  // IFC
        writeAscii("", 3)                   // IMFLT
        writeAsciiRJ("0", 1)                // NLUTS
    }
    writeAsciiRJ("0", 1)                    // ISYNC
    writeAscii(fx.imode.toString(), 1)      // IMODE
    writeAsciiRJ(fx.blocksPerRow.toString(), 4)
    writeAsciiRJ(fx.blocksPerCol.toString(), 4)
    writeAsciiRJ(fx.pixelsPerBlockH.toString(), 4)
    writeAsciiRJ(fx.pixelsPerBlockV.toString(), 4)
    writeAsciiRJ(fx.nbpp.toString(), 2)
    writeAsciiRJ("1", 3)                    // IDLVL
    writeAsciiRJ("0", 3)                    // IALVL
    writeAscii("0000000000", 10)            // ILOC
    writeAscii("1.0 ", 4)                   // IMAG
    writeAsciiRJ("0", 5)                    // UDIDL
    writeAsciiRJ("0", 5)                    // IXSHDL
    val subhWritten = p - subStart
    check(subhWritten == lish) { "Image subheader expected $lish bytes, wrote $subhWritten" }

    // --- Image data ---
    val dataStart = p
    if (fx.masked) {
        // Block-mask record (BMR) only. We declare BMRLNTH > 0 and TMRLNTH = 0.
        val tpxcdBytes = if (fx.transparentCode != null) (fx.nbpp + 7) / 8 else 0
        val imdatoff = 4 + 2 + 2 + 2 + tpxcdBytes + maskEntries * 4
        // IMDATOFF
        out[p] = ((imdatoff ushr 24) and 0xFF).toByte()
        out[p + 1] = ((imdatoff ushr 16) and 0xFF).toByte()
        out[p + 2] = ((imdatoff ushr 8) and 0xFF).toByte()
        out[p + 3] = (imdatoff and 0xFF).toByte()
        p += 4
        // BMRLNTH
        val bmrLen = maskEntries * 4
        out[p] = ((bmrLen ushr 8) and 0xFF).toByte()
        out[p + 1] = (bmrLen and 0xFF).toByte()
        p += 2
        // TMRLNTH
        out[p] = 0; out[p + 1] = 0; p += 2
        // TPXCDLNTH (bits)
        val tpxcdLnth = if (fx.transparentCode != null) fx.nbpp else 0
        out[p] = ((tpxcdLnth ushr 8) and 0xFF).toByte()
        out[p + 1] = (tpxcdLnth and 0xFF).toByte()
        p += 2
        // TPXCD
        if (fx.transparentCode != null) {
            val v = fx.transparentCode
            if (tpxcdBytes == 1) {
                out[p] = (v and 0xFF).toByte(); p += 1
            } else {
                out[p] = ((v ushr 8) and 0xFF).toByte()
                out[p + 1] = (v and 0xFF).toByte()
                p += 2
            }
        }
        // BMR — block offsets. Present blocks: offset = cumulative position;
        // missing blocks: 0xFFFFFFFF.
        var cursor = 0
        for (i in 0 until maskEntries) {
            val isMissing = i in fx.maskedBlocks
            val value = if (isMissing) -1 else cursor
            out[p] = ((value ushr 24) and 0xFF).toByte()
            out[p + 1] = ((value ushr 16) and 0xFF).toByte()
            out[p + 2] = ((value ushr 8) and 0xFF).toByte()
            out[p + 3] = (value and 0xFF).toByte()
            p += 4
            if (!isMissing) cursor += bytesPerBlock
        }
    }

    // Now the actual block data. We walk in spec order per IMODE.
    fun writeSample(value: Int) {
        if (bytesPerSample == 1) {
            out[p] = (value and 0xFF).toByte()
            p += 1
        } else {
            out[p] = ((value ushr 8) and 0xFF).toByte()
            out[p + 1] = (value and 0xFF).toByte()
            p += 2
        }
    }

    when (fx.imode) {
        'B' -> {
            for (by in 0 until fx.blocksPerCol) {
                for (bx in 0 until fx.blocksPerRow) {
                    for (band in 0 until fx.nbands) {
                        val gIdx = (by * fx.blocksPerRow + bx) * fx.nbands + band
                        if (fx.masked && gIdx in fx.maskedBlocks) continue
                        for (py in 0 until fx.pixelsPerBlockV) {
                            for (px in 0 until fx.pixelsPerBlockH) {
                                val row = by * fx.pixelsPerBlockV + py
                                val col = bx * fx.pixelsPerBlockH + px
                                writeSample(fx.pixel(band, row, col))
                            }
                        }
                    }
                }
            }
        }
        'P' -> {
            for (by in 0 until fx.blocksPerCol) {
                for (bx in 0 until fx.blocksPerRow) {
                    val gIdx = by * fx.blocksPerRow + bx
                    if (fx.masked && gIdx in fx.maskedBlocks) continue
                    for (py in 0 until fx.pixelsPerBlockV) {
                        for (px in 0 until fx.pixelsPerBlockH) {
                            for (band in 0 until fx.nbands) {
                                val row = by * fx.pixelsPerBlockV + py
                                val col = bx * fx.pixelsPerBlockH + px
                                writeSample(fx.pixel(band, row, col))
                            }
                        }
                    }
                }
            }
        }
        'R' -> {
            for (by in 0 until fx.blocksPerCol) {
                for (bx in 0 until fx.blocksPerRow) {
                    val gIdx = by * fx.blocksPerRow + bx
                    if (fx.masked && gIdx in fx.maskedBlocks) continue
                    for (py in 0 until fx.pixelsPerBlockV) {
                        for (band in 0 until fx.nbands) {
                            for (px in 0 until fx.pixelsPerBlockH) {
                                val row = by * fx.pixelsPerBlockV + py
                                val col = bx * fx.pixelsPerBlockH + px
                                writeSample(fx.pixel(band, row, col))
                            }
                        }
                    }
                }
            }
        }
        'S' -> {
            for (band in 0 until fx.nbands) {
                for (by in 0 until fx.blocksPerCol) {
                    for (bx in 0 until fx.blocksPerRow) {
                        val gIdx = band * (fx.blocksPerCol * fx.blocksPerRow) +
                            by * fx.blocksPerRow + bx
                        if (fx.masked && gIdx in fx.maskedBlocks) continue
                        for (py in 0 until fx.pixelsPerBlockV) {
                            for (px in 0 until fx.pixelsPerBlockH) {
                                val row = by * fx.pixelsPerBlockV + py
                                val col = bx * fx.pixelsPerBlockH + px
                                writeSample(fx.pixel(band, row, col))
                            }
                        }
                    }
                }
            }
        }
        else -> error("Unsupported imode in fixture: ${fx.imode}")
    }
    check(p == out.size) { "fixture write expected ${out.size} bytes, wrote $p" }
    val written = p - dataStart
    check(written.toLong() == li) { "image data expected $li bytes, wrote $written" }
    return out
}

private fun computeSubheaderLength(fx: NitfFixture): Int {
    var len = 2 + 10 + 14 + 17 + 80 + 167 + 1 + 42 + 8 + 8 + 3 + 8 + 8 + 2 + 1 + 1
    if (fx.icords != ' ') len += 60
    len += 1 // NICOM
    len += 2 // IC
    // No COMRAT (uncompressed only here).
    len += 1 // NBANDS
    len += fx.nbands * (2 + 6 + 1 + 3 + 1)
    len += 1 + 1 + 4 + 4 + 4 + 4 + 2 + 3 + 3 + 10 + 4 + 5 + 5
    return len
}

/** `±dd.ddd±ddd.ddd` (15 chars) × 4 corners (NW, NE, SE, SW). */
internal fun formatDecimalIgeolo(latS: Double, latN: Double, lonW: Double, lonE: Double): String {
    val nw = formatDecimalCorner(latN, lonW)
    val ne = formatDecimalCorner(latN, lonE)
    val se = formatDecimalCorner(latS, lonE)
    val sw = formatDecimalCorner(latS, lonW)
    return nw + ne + se + sw
}

private fun formatDecimalCorner(lat: Double, lon: Double): String {
    val latStr = String.format(Locale.ROOT, if (lat >= 0) "+%06.3f" else "-%06.3f", if (lat >= 0) lat else -lat)
    val lonStr = String.format(Locale.ROOT, if (lon >= 0) "+%07.3f" else "-%07.3f", if (lon >= 0) lon else -lon)
    return latStr + lonStr
}
