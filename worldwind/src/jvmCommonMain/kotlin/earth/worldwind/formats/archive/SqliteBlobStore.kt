package earth.worldwind.formats.archive

/** Cesium `.3dtiles` SQLite schema (3d-tiles-tools):
 *  `CREATE TABLE media (key TEXT PRIMARY KEY, content BLOB)`. Keys are tileset-relative paths. */
internal const val MEDIA_TABLE = "media"
internal const val MEDIA_KEY_COLUMN = "key"
internal const val MEDIA_CONTENT_COLUMN = "content"

/**
 * Minimal read-only accessor over a `.3dtiles` package's [MEDIA_TABLE]. expect/actual because JVM
 * uses JDBC (sqlite-jdbc, pulled in transitively by geopackage-java) while Android uses the framework
 * `SQLiteDatabase`. Calls are blocking; the caller wraps them on an IO dispatcher. Implementations are
 * thread-safe — reads arrive concurrently from the tile-fetch pool.
 */
internal expect class SqliteBlobStore(pathName: String) {
    /** The `content` BLOB for [key], or null when no such row exists. */
    fun blobForKey(key: String): ByteArray?

    /** True when a row with [key] exists (cheap `SELECT 1 … LIMIT 1`, no blob read). */
    fun containsKey(key: String): Boolean

    /** Number of rows in the media table. */
    fun rowCount(): Int

    /** Close the database handle. Idempotent. */
    fun close()
}
