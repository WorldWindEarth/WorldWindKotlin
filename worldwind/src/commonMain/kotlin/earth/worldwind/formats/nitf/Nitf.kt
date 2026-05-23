package earth.worldwind.formats.nitf

import earth.worldwind.formats.BinaryDataView

/**
 * Top-level entry point for reading National Imagery Transmission Format
 * (NITF) 2.1 and NATO Secondary Imagery Format (NSIF) 1.0 files.
 *
 * The reader is cross-platform — all parsing lives in `commonMain` on top of
 * [BinaryDataView] — and currently covers:
 *  - File header + segment offset table (image / graphic / text / DES / RES).
 *  - Image segment subheaders with full metadata + IGEOLO decoding for the
 *    G (DMS) / D (decimal degrees) / N (UTM north) / S (UTM south) coord
 *    systems (MGRS = `U` is parsed but not converted yet).
 *  - Uncompressed pixel data (IC=NC and IC=NM, with the NM block mask) for
 *    NBPP ∈ {8, 16}, NBANDS ∈ {1, 3, 4}, all four IMODE values.
 *
 * Compressed segments (JPEG / JPEG-2000 / VQ / bilevel) require platform image
 * decoders and ship in Phase 2.
 *
 * Two entry points cover the common needs:
 *  - [Nitf.parse] — full parse from a byte array; segment headers populated.
 *  - [Nitf.readMetadata] — file header only (just enough to enumerate
 *    segments without paying for image subheader decoding).
 *
 * Reading pixels:
 *  ```
 *  val nitf = Nitf.parse(bytes)
 *  val seg = nitf.imageSegments.first()
 *  if (seg.compression == NitfCompression.NC) {
 *      val argb = NitfImageReader.decodeArgb(seg, bytes)
 *  }
 *  ```
 */
class Nitf internal constructor(
    val fileHeader: NitfFileHeader,
    val imageSegments: List<NitfImageSegment>,
) {

    /** Format + version pair (NITF 02.10 or NSIF 01.00). */
    val format: NitfFormat get() = fileHeader.format

    companion object {
        /** Magic bytes that prefix every supported file (`NITF` or `NSIF`). */
        fun isSupported(bytes: ByteArray): Boolean {
            if (bytes.size < 9) return false
            val tag = bytes.copyOfRange(0, 4).decodeToString()
            val ver = bytes.copyOfRange(4, 9).decodeToString()
            return NitfFormat.fromCodes(tag, ver) != null
        }

        /** Full parse — file header + all image subheaders. */
        fun parse(bytes: ByteArray): Nitf {
            val reader = NitfReader(BinaryDataView(bytes))
            val header = NitfFileHeader.parse(reader)
            val images = header.imageSegments.map { loc ->
                NitfImageSegment.parse(reader, loc.headerOffset, loc.headerLength, loc.dataLength)
            }
            return Nitf(header, images)
        }

        /** Header-only parse — segments enumerable but image subheaders unread. */
        fun readMetadata(bytes: ByteArray): NitfFileHeader =
            NitfFileHeader.parse(NitfReader(BinaryDataView(bytes)))
    }
}
