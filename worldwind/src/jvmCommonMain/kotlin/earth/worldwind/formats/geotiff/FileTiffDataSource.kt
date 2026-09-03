package earth.worldwind.formats.geotiff

import java.io.File
import java.io.RandomAccessFile

/**
 * [TiffDataSource] over a local file, read through a [RandomAccessFile] so a tiled GeoTIFF
 * is paged block by block instead of loaded whole — the point of the format for rasters
 * that don't fit in memory.
 *
 * Reads are serialized on the shared file handle, which is what makes the source safe to
 * hand to concurrently decoding tiles.
 */
class FileTiffDataSource(private val file: File) : TiffDataSource {
    private val raf = RandomAccessFile(file, "r")

    override val size: Long = raf.length()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= size || length <= 0) return ByteArray(0)
        val count = minOf(length.toLong(), size - offset).toInt()
        val bytes = ByteArray(count)
        synchronized(raf) {
            raf.seek(offset)
            raf.readFully(bytes)
        }
        return bytes
    }

    override fun close() = raf.close()

    override fun toString() = file.toString()
}
