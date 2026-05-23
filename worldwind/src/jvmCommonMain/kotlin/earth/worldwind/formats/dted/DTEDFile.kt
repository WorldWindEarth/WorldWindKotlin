package earth.worldwind.formats.dted

import earth.worldwind.formats.BinaryDataView
import earth.worldwind.formats.dted.DTED.Companion.DATA_OFFSET
import earth.worldwind.formats.dted.DTED.Companion.computeRange
import earth.worldwind.formats.dted.DTED.Companion.decodeColumn
import earth.worldwind.formats.dted.DTED.Companion.parseHeader
import earth.worldwind.formats.dted.DTED.Companion.recordSize
import java.io.File
import java.io.RandomAccessFile

/** Read only the UHL/DSI/ACC headers from a DTED file. Reads the first 3428 bytes and
 *  closes the file — useful when indexing a library of tiles without holding the
 *  full elevation grid (~26 MB for .dt2) in memory. */
fun DTED.Companion.readMetadata(file: File): DTED.Metadata =
    RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(DATA_OFFSET)
        raf.readFully(header)
        parseHeader(BinaryDataView(header))
    }

/** Streaming DTED reader. Reads the header, then walks column records one at a time
 *  via [RandomAccessFile] — peak memory is the header (~3.4 KB) plus a single
 *  per-column record buffer (~7 KB on .dt2) plus the output grid, instead of the
 *  whole file (~26 MB on .dt2). */
fun DTED.Companion.read(file: File): DTED = RandomAccessFile(file, "r").use { raf ->
    val headerBytes = ByteArray(DATA_OFFSET)
    raf.readFully(headerBytes)
    val meta = parseHeader(BinaryDataView(headerBytes))
    val rec = recordSize(meta.height)
    val elevations = ShortArray(meta.width * meta.height)
    val recordBytes = ByteArray(rec)
    val recordView = BinaryDataView(recordBytes)
    for (x in 0 until meta.width) {
        raf.readFully(recordBytes)
        // The shared decoder treats the buffer as starting at offset 0 — the record is
        // self-contained, the absolute file offset doesn't matter for parsing.
        decodeColumn(recordView, 0, x, meta.width, meta.height, elevations)
    }
    val (minE, maxE) = computeRange(elevations)
    DTED(
        meta.sector, meta.origin, meta.width, meta.height, meta.level, meta.classLevel,
        elevations, minE, maxE
    )
}
