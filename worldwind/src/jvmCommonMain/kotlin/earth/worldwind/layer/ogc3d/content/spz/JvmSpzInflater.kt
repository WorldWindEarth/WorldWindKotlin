package earth.worldwind.layer.ogc3d.content.spz

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/** JVM + Android [SpzInflater] backed by [GZIPInputStream]. Pre-sizes the output from the
 *  GZIP trailer's ISIZE field (avoiding the BAOS internal-buffer + final-copy alloc), with
 *  a growable fallback when ISIZE is unusable. */
class JvmSpzInflater : SpzInflater {
    override fun inflate(bytes: ByteArray): ByteArray {
        // GZIP trailer: CRC32 (4B) + ISIZE (4B) little-endian, after a 10B header + at least
        // a minimal deflate block. Anything shorter than 18B is malformed.
        if (bytes.size < 18) return inflateGrowable(bytes)
        val isize = ((bytes[bytes.size - 4].toInt() and 0xFF)) or
            ((bytes[bytes.size - 3].toInt() and 0xFF) shl 8) or
            ((bytes[bytes.size - 2].toInt() and 0xFF) shl 16) or
            ((bytes[bytes.size - 1].toInt() and 0xFF) shl 24)
        // Long arithmetic in the bound avoids Int overflow at ~67 MiB compressed input.
        if (isize <= 0 || isize.toLong() > bytes.size.toLong() * 64L) return inflateGrowable(bytes)

        val out = ByteArray(isize)
        GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
            var offset = 0
            while (offset < isize) {
                val n = gz.read(out, offset, isize - offset)
                if (n <= 0) break
                offset += n
            }
            if (offset != isize) return inflateGrowable(bytes)
        }
        return out
    }

    private fun inflateGrowable(bytes: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
            val out = java.io.ByteArrayOutputStream(bytes.size * 4)
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = gz.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}

/** JVM + Android default: [GZIPInputStream]-backed [JvmSpzInflater]. */
actual fun installDefaultSpzInflater() {
    if (SpzGaussianLoader.inflater == null) SpzGaussianLoader.inflater = JvmSpzInflater()
}
