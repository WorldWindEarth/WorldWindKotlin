package earth.worldwind.formats.archive

/**
 * Read-only, open-in-place view over a local content archive — a path → bytes store, backed by
 * whatever container the file actually is. Implementations today:
 * - [ZipFileArchive] — a ZIP (ArcGIS SLPK, Cesium 3D Tiles Archive `.3tz` / 3TZ).
 * - [Sqlite3dTilesArchive] — a SQLite package (Cesium `.3dtiles`, a `media(key, content)` table).
 *
 * The point in every case is random access without reading or copying the whole file: resolve a
 * logical entry path to its (decompressed) bytes on demand. Datasets are multi-GB, so RAM stays
 * bounded by the index plus whatever is currently resident, NOT file size. The handle stays open for
 * the layer's lifetime; [close] releases it.
 *
 * [readEntry] returns the *logical* resource: if the stored entry is GZIP-compressed (I3S gzips its
 * JSON + binary buffers; some `.3dtiles` producers gzip each blob), the implementation inflates it
 * transparently. A plain ZIP `.3tz` and raw-stored `.3dtiles` blobs pass through unchanged.
 */
interface ReadOnlyArchive {
    /** Number of entries in the archive (diagnostic; also a proxy for index-memory pressure). */
    val entryCount: Int

    /** True when [path] resolves to an entry. Cheap index/metadata lookup, no full read. */
    fun hasEntry(path: String): Boolean

    /**
     * Read and decompress the entry at [path] (archive-internal path, e.g. `content/0/0.b3dm` or
     * `nodes/1/geometries/0.bin`). Returns null when the entry is absent. Runs the blocking read on
     * an IO dispatcher in the implementation. Throws only on genuine IO failure, not on a miss.
     */
    suspend fun readEntry(path: String): ByteArray?

    /** Release the underlying file handle. Idempotent. */
    fun close()
}
