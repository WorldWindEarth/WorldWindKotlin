package earth.worldwind.formats.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * [ReadOnlyArchive] backed by [java.util.zip.ZipFile] over a direct filesystem path (JVM +
 * Android). `ZipFile` opens with random access, reads only the central directory up front, and
 * inflates individual entries on demand — so opening a 15 GB archive does not read 15 GB. Requires
 * a real file path; the app is expected to hand us one it can open directly (no SAF `content://`
 * fd handling needed for that case). ZIP64 is supported on modern JVM/Android.
 *
 * TODO (mobile scale): `ZipFile` retains a `ZipEntry` for every archive entry — for a multi-million
 * entry package that is hundreds of MB of RAM. The production path should be a `CompactIndexArchive`
 * built on `RandomAccessFile`: parse EOCD64 + the central directory once into an open-addressed
 * `pathHash -> (offset, size)` `long[]` index (3TZ can shortcut this via its `@3dtilesIndex1@`
 * index entry), drop the entry names, and `pread` entries by offset. This `ZipFile` impl is fine
 * for smaller datasets; measure entry count on a real dataset before shipping it as the default.
 */
class ZipFileArchive private constructor(private val zip: ZipFile) : ReadOnlyArchive {
    override val entryCount: Int get() = zip.size()

    override fun hasEntry(path: String) = entryOf(path) != null

    override suspend fun readEntry(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val entry = entryOf(path) ?: return@withContext null
        val raw = zip.getInputStream(entry).use { it.readBytes() }
        maybeGunzip(raw)
    }

    override fun close() { runCatching { zip.close() } }

    /** Resolve an entry, falling back to a `.gz`-suffixed name — many SLPKs store gzipped resources
     *  with the `.gz` extension baked into the entry name (e.g. `3dSceneLayer.json.gz`). The exact
     *  name wins first, so raw-named archives (3TZ) are unaffected. */
    private fun entryOf(path: String): java.util.zip.ZipEntry? {
        val name = normalizeEntryPath(path)
        return zip.getEntry(name) ?: zip.getEntry("$name.gz")
    }

    companion object {
        /** Open the archive at [path]. The file must be directly openable (not a SAF content URI). */
        fun open(path: String): ZipFileArchive = open(File(path))

        fun open(file: File): ZipFileArchive = ZipFileArchive(ZipFile(file))
    }
}
