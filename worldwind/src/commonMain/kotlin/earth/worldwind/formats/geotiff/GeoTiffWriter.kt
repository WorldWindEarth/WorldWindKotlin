package earth.worldwind.formats.geotiff

/**
 * Cross-platform TIFF writer for the engine's elevation cache. Produces a minimal but
 * spec-compliant baseline TIFF: little-endian header, single strip, uncompressed,
 * `BlackIsZero` photometric, single sample per pixel, 32-bit IEEE float values. Reads back
 * cleanly through [GeoTiffReader] and any standard TIFF library.
 *
 * Replaces the JVM-only `mil.nga.tiff.TiffWriter` path that previously serialised
 * GeoPackage cache tiles - now the same code produces tiles on every KMP target.
 */
object GeoTiffWriter {

    /**
     * Encode a `width × height` grid of float elevation samples (row-major, top-row first)
     * into a TIFF byte array. The `Float.MAX_VALUE` null-data sentinel that the reader
     * recognises is written through unchanged.
     */
    fun writeFloatGrayscaleTiff(pixels: FloatArray, width: Int, height: Int): ByteArray {
        require(pixels.size == width * height) {
            "Expected ${width * height} samples for ${width}x$height, got ${pixels.size}"
        }
        // 11 IFD entries are the minimal set readers like libtiff / mil.nga.tiff /
        // GeoTiffReader require to interpret a baseline FLOAT32 grayscale image.
        val ifdEntryCount = 11
        // IFD layout: 2-byte entry count + N × 12-byte entries + 4-byte next-IFD offset.
        val ifdSize = 2 + ifdEntryCount * 12 + 4
        val headerSize = 8
        val ifdOffset = headerSize
        val stripOffset = headerSize + ifdSize
        val stripByteCount = width * height * 4
        val totalSize = stripOffset + stripByteCount
        val view = BinaryDataView(ByteArray(totalSize))
        val le = true /* little endian, written as 'II' */

        // Header.
        view.setUint16(0, 0x4949, le) /* II */
        view.setUint16(2, 42, le)
        view.setUint32(4, ifdOffset, le)

        // IFD.
        var off = ifdOffset
        view.setUint16(off, ifdEntryCount, le); off += 2
        // Each entry packs `count` short values (2 bytes) into the 4-byte value field if
        // they fit; longer values write to the strip-end area. For our minimal set every
        // entry's payload fits in 4 bytes, so we always inline.
        off = writeShortEntry(view, off, TiffConstants.IFDTag.IMAGE_WIDTH, width, le)
        off = writeShortEntry(view, off, TiffConstants.IFDTag.IMAGE_LENGTH, height, le)
        off = writeShortEntry(view, off, TiffConstants.IFDTag.BITS_PER_SAMPLE, 32, le)
        off = writeShortEntry(view, off, TiffConstants.IFDTag.COMPRESSION, TiffConstants.Compression.UNCOMPRESSED, le)
        off = writeShortEntry(view, off, TiffConstants.IFDTag.PHOTOMETRIC_INTERPRETATION, 1, le) /* BlackIsZero */
        // StripOffsets is LONG.
        off = writeLongEntry(view, off, TiffConstants.IFDTag.STRIP_OFFSETS, stripOffset, le)
        off = writeShortEntry(view, off, TiffConstants.IFDTag.SAMPLES_PER_PIXEL, 1, le)
        off = writeShortEntry(view, off, TiffConstants.IFDTag.ROWS_PER_STRIP, height, le)
        off = writeLongEntry(view, off, TiffConstants.IFDTag.STRIP_BYTE_COUNTS, stripByteCount, le)
        off = writeShortEntry(view, off, TiffConstants.IFDTag.PLANAR_CONFIGURATION, 1, le) /* Chunky */
        off = writeShortEntry(view, off, TiffConstants.IFDTag.SAMPLE_FORMAT, TiffConstants.SampleFormat.IEEE_FLOAT, le)
        // Next-IFD offset = 0 (no more directories).
        view.setUint32(off, 0, le)

        // Strip data.
        var p = stripOffset
        for (i in pixels.indices) {
            view.setFloat32(p, pixels[i], le)
            p += 4
        }
        return view.asByteArray()
    }

    private fun writeShortEntry(view: BinaryDataView, off: Int, tag: Int, value: Int, le: Boolean): Int {
        view.setUint16(off, tag, le)
        view.setUint16(off + 2, TiffConstants.Type.SHORT, le)
        view.setUint32(off + 4, 1, le) /* count */
        view.setUint16(off + 8, value, le)
        view.setUint16(off + 10, 0, le) /* high half of the 4-byte value field is unused */
        return off + 12
    }

    private fun writeLongEntry(view: BinaryDataView, off: Int, tag: Int, value: Int, le: Boolean): Int {
        view.setUint16(off, tag, le)
        view.setUint16(off + 2, TiffConstants.Type.LONG, le)
        view.setUint32(off + 4, 1, le) /* count */
        view.setUint32(off + 8, value, le)
        return off + 12
    }
}
