@file:OptIn(ExperimentalForeignApi::class)

package earth.worldwind.formats.dted

import earth.worldwind.formats.BinaryDataView
import earth.worldwind.formats.dted.DTED.Companion.DATA_OFFSET
import earth.worldwind.formats.dted.DTED.Companion.computeRange
import earth.worldwind.formats.dted.DTED.Companion.decodeColumn
import earth.worldwind.formats.dted.DTED.Companion.parseHeader
import earth.worldwind.formats.dted.DTED.Companion.recordSize
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength

/** Read only the UHL/DSI/ACC headers from a DTED file on disk. Reads the first 3428
 *  bytes and closes the handle — useful when indexing a library of tiles without
 *  loading the elevation grid (~26 MB for .dt2). iOS analogue of the JVM
 *  [readMetadata] overload. */
fun DTED.Companion.readMetadata(path: String): DTED.Metadata {
    val handle = NSFileHandle.fileHandleForReadingAtPath(path)
        ?: error("DTED: cannot open file '$path'")
    try {
        val headerBytes = readDataAsByteArray(handle, DATA_OFFSET)
        return parseHeader(BinaryDataView(headerBytes))
    } finally {
        handle.closeFile()
    }
}

/** Streaming DTED reader. Reads the header, then walks the per-column records one
 *  at a time via [NSFileHandle] — peak memory is the header (~3.4 KB) plus a single
 *  per-column record (~7 KB on .dt2) plus the output grid, instead of the entire
 *  file (~26 MB on .dt2). iOS analogue of the JVM [read] overload. */
fun DTED.Companion.read(path: String): DTED {
    val handle = NSFileHandle.fileHandleForReadingAtPath(path)
        ?: error("DTED: cannot open file '$path'")
    try {
        val headerBytes = readDataAsByteArray(handle, DATA_OFFSET)
        val meta = parseHeader(BinaryDataView(headerBytes))
        val rec = recordSize(meta.height)
        val elevations = ShortArray(meta.width * meta.height)
        // Reuse one ByteArray across columns; BinaryDataView wraps it once and reads
        // the freshly-populated bytes on every iteration.
        val recordBytes = ByteArray(rec)
        val recordView = BinaryDataView(recordBytes)
        for (x in 0 until meta.width) {
            readDataInto(handle, recordBytes)
            decodeColumn(recordView, 0, x, meta.width, meta.height, elevations)
        }
        val (minE, maxE) = computeRange(elevations)
        return DTED(
            meta.sector, meta.origin, meta.width, meta.height, meta.level, meta.classLevel,
            elevations, minE, maxE
        )
    } finally {
        handle.closeFile()
    }
}

private fun readDataAsByteArray(handle: NSFileHandle, length: Int): ByteArray {
    val data = handle.readDataOfLength(length.toULong())
    val bytes = ByteArray(data.length.toInt())
    copyNSDataInto(data, bytes)
    return bytes
}

/** Reads exactly `dest.size` bytes from [handle] into [dest]. Allocates one [NSData]
 *  per call; reusing the destination ByteArray across calls is the caller's job. */
private fun readDataInto(handle: NSFileHandle, dest: ByteArray) {
    val data = handle.readDataOfLength(dest.size.toULong())
    copyNSDataInto(data, dest)
}

private fun copyNSDataInto(data: NSData, dest: ByteArray) {
    val length = data.length.toInt()
    require(length == dest.size) {
        "DTED: short read — expected ${dest.size} bytes, got $length"
    }
    val bytesPtr = data.bytes ?: return
    val src = bytesPtr.reinterpret<ByteVar>()
    for (i in 0 until length) dest[i] = src[i]
}
