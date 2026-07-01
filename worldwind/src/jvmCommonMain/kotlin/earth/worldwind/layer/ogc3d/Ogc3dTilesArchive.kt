package earth.worldwind.layer.ogc3d

import earth.worldwind.formats.archive.ReadOnlyArchive
import earth.worldwind.formats.archive.Sqlite3dTilesArchive
import earth.worldwind.formats.archive.ZipFileArchive
import earth.worldwind.layer.cache.BlobStore
import earth.worldwind.layer.cache.NoOpBlobStore
import earth.worldwind.layer.ogc3d.auth.NoAuthProvider
import earth.worldwind.layer.ogc3d.stream.ArchiveTileByteSource
import java.io.File

/**
 * Open a **Cesium 3D Tiles Archive** in place from a local file — a single-file package of an ordinary
 * 3D Tiles dataset. Both container forms are auto-detected by magic bytes: `.3tz` (a ZIP, via
 * [ZipFileArchive]) and `.3dtiles` (a SQLite `media(key, content)` DB, via [Sqlite3dTilesArchive]).
 *
 * Nothing is copied or extracted — the archive is wired in purely as the layer's byte transport via an
 * [ArchiveTileByteSource] (root URI `3dtiles://{archiveId}/{rootEntry}`, whose `scheme://authority/`
 * shape TilesetParser resolves child content paths against), so the standard parse/decode/traverse/GPU
 * pipeline runs unchanged with no [Ogc3dTilesLayer] subclass. Scope: JVM + Android. Call
 * [Ogc3dTilesLayer.shutdown] when done — it closes the archive handle through the byte source.
 *
 * @param file a directly-openable filesystem path to the archive (not a SAF content URI).
 * @param rootEntry archive-internal path of the root document; the spec mandates `tileset.json` at root.
 * @param blobStore usually [NoOpBlobStore] — bytes are already local; pass one only to share payloads.
 */
fun openArchived3dTilesLayer(
    file: File,
    rootEntry: String = "tileset.json",
    displayName: String = file.name,
    blobStore: BlobStore = NoOpBlobStore,
    fetchConcurrency: Int = 8,
): Ogc3dTilesLayer {
    val archive = openArchive(file)
    // URL-safe, stable-per-file id for the URI authority + cache-key disambiguation.
    val archiveId = file.absolutePath.hashCode().toUInt().toString(16)
    val byteSource = ArchiveTileByteSource(archive, ArchiveTileByteSource.THREEDTILES_SCHEME)
    return Ogc3dTilesLayer(
        source = TilesetSource(
            rootUri = ArchiveTileByteSource.rootUri(archiveId, rootEntry),
            authProvider = NoAuthProvider,
            displayName = displayName,
        ),
        displayName = displayName,
        blobStore = blobStore,
        fetchConcurrency = fetchConcurrency,
        byteSource = byteSource,
    )
}

/** Convenience overload accepting a filesystem path string. */
fun openArchived3dTilesLayer(
    path: String,
    rootEntry: String = "tileset.json",
    displayName: String? = null,
    blobStore: BlobStore = NoOpBlobStore,
    fetchConcurrency: Int = 8,
): Ogc3dTilesLayer = File(path).let {
    openArchived3dTilesLayer(it, rootEntry, displayName ?: it.name, blobStore, fetchConcurrency)
}

/** Pick the reader by sniffing the file's magic bytes, so a mis-extensioned archive still opens:
 *  SQLite (`.3dtiles`) vs. ZIP (`.3tz`). */
private fun openArchive(file: File): ReadOnlyArchive =
    if (isSqlite(file)) Sqlite3dTilesArchive.open(file) else ZipFileArchive.open(file)

/** True when [file] begins with the SQLite header string `"SQLite format 3\u0000"` (16 bytes). */
private fun isSqlite(file: File): Boolean {
    val header = ByteArray(16)
    var off = 0
    file.inputStream().use { input ->
        while (off < header.size) {
            val r = input.read(header, off, header.size - off)
            if (r < 0) break
            off += r
        }
    }
    return off >= 16 && header.decodeToString(0, 15) == "SQLite format 3"
}
