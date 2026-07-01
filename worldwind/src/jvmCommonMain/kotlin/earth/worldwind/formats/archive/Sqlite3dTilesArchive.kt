package earth.worldwind.formats.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [ReadOnlyArchive] over a Cesium `.3dtiles` package — a SQLite database whose `media(key, content)`
 * table maps each tileset-relative path to its (optionally gzipped) bytes. This is the SQLite sibling
 * of [ZipFileArchive] (which reads the `.3tz` / 3TZ ZIP form); both feed the same
 * [earth.worldwind.layer.ogc3d.stream.ArchiveTileByteSource] transport, so the layer pipeline is
 * identical regardless of container.
 *
 * Entry paths are relative, e.g. `tileset.json`, `content/0/0.b3dm`. Scope: JVM + Android.
 */
class Sqlite3dTilesArchive private constructor(private val db: SqliteBlobStore) : ReadOnlyArchive {
    override val entryCount: Int get() = db.rowCount()

    override fun hasEntry(path: String) = db.containsKey(normalizeEntryPath(path))

    override suspend fun readEntry(path: String): ByteArray? = withContext(Dispatchers.IO) {
        db.blobForKey(normalizeEntryPath(path))?.let(::maybeGunzip)
    }

    override fun close() = db.close()

    companion object {
        /** Open the `.3dtiles` SQLite package at [path] (a directly-openable filesystem path). */
        fun open(path: String): Sqlite3dTilesArchive = Sqlite3dTilesArchive(SqliteBlobStore(path))

        fun open(file: File): Sqlite3dTilesArchive = open(file.absolutePath)
    }
}
