package earth.worldwind.util.ormlite

import com.j256.ormlite.jdbc.JdbcConnectionSource
import com.j256.ormlite.support.ConnectionSource
import com.j256.ormlite.support.DatabaseConnection

actual fun initConnection(pathName: String, readOnly: Boolean): ConnectionSource {
    // journal_mode + synchronous are universally supported xerial URL pragmas; cache_size and
    // wal_autocheckpoint go via execSQL post-open to match Android and avoid silent-drop risk.
    val url = if (readOnly) "jdbc:sqlite:$pathName" else "jdbc:sqlite:$pathName?journal_mode=WAL&synchronous=NORMAL"
    val source = JdbcConnectionSource(url)
    if (!readOnly) {
        val conn = source.getReadWriteConnection(null)
        try {
            conn.executeStatement("PRAGMA cache_size = -64000", DatabaseConnection.DEFAULT_RESULT_FLAGS)
            conn.executeStatement("PRAGMA wal_autocheckpoint = 1000", DatabaseConnection.DEFAULT_RESULT_FLAGS)
        } finally {
            source.releaseConnection(conn)
        }
    }
    return source
}
