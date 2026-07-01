package earth.worldwind.formats.archive

import android.database.sqlite.SQLiteDatabase

/**
 * Android [SqliteBlobStore] over the framework `SQLiteDatabase`, opened read-only. `SQLiteDatabase`
 * serializes access internally, so no explicit locking is needed for concurrent tile reads.
 */
internal actual class SqliteBlobStore actual constructor(pathName: String) {
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(pathName, null, SQLiteDatabase.OPEN_READONLY)

    actual fun blobForKey(key: String): ByteArray? = db.rawQuery(
        "SELECT $MEDIA_CONTENT_COLUMN FROM $MEDIA_TABLE WHERE $MEDIA_KEY_COLUMN = ? LIMIT 1",
        arrayOf(key),
    ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }

    actual fun containsKey(key: String): Boolean = db.rawQuery(
        "SELECT 1 FROM $MEDIA_TABLE WHERE $MEDIA_KEY_COLUMN = ? LIMIT 1",
        arrayOf(key),
    ).use { it.moveToFirst() }

    actual fun rowCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM $MEDIA_TABLE", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    actual fun close() { runCatching { db.close() } }
}
