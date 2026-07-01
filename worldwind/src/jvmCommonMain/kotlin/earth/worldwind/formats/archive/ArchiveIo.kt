package earth.worldwind.formats.archive

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/** Archive entry paths are forward-slash and relative; tolerate an accidental leading slash. */
internal fun normalizeEntryPath(path: String): String = path.trimStart('/')

/** Inflate if the resource is GZIP'd (I3S gzips JSON + binary buffers; some `.3dtiles` producers
 *  gzip each blob). Raw ZIP `.3tz` content + textures pass through unchanged — b3dm/glb/JSON magics
 *  never collide with gzip's `0x1F8B`. */
internal fun maybeGunzip(bytes: ByteArray): ByteArray {
    if (bytes.size < 2 || bytes[0].toInt() and 0xFF != 0x1F || bytes[1].toInt() and 0xFF != 0x8B) return bytes
    return GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}
