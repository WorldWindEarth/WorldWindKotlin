package earth.worldwind.formats.geotiff

/**
 * Random-access byte source over a TIFF container. A tiled GeoTIFF is read block-by-block
 * on demand — never slurped whole — so the same 2 GB orthophoto that would blow the heap
 * as a `ByteArray` streams one 256×256 tile at a time through this seam.
 *
 * Implementations must tolerate **concurrent** [read] calls: pyramid tiles decode in
 * parallel on the IO dispatcher, so a stateful backend (a file handle with a seek
 * position) has to serialize internally.
 *
 * [read] returns at most [length] bytes and fewer only at end-of-source; callers treat a
 * short read as a truncated file.
 */
interface TiffDataSource {
    /** Total byte length of the container. */
    val size: Long

    /** Read up to [length] bytes starting at [offset]. */
    fun read(offset: Long, length: Int): ByteArray

    /** Release the underlying handle. Idempotent; no-op by default. */
    fun close() {}
}

/** [TiffDataSource] over an in-memory TIFF — for byte payloads that already live on the heap
 *  (a WCS response, a test fixture, a small file on a platform without random-access I/O). */
class ByteArrayTiffDataSource(private val bytes: ByteArray) : TiffDataSource {
    override val size get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= bytes.size || length <= 0) return ByteArray(0)
        val from = offset.toInt()
        val to = (from.toLong() + length).coerceAtMost(bytes.size.toLong()).toInt()
        return bytes.copyOfRange(from, to)
    }
}
