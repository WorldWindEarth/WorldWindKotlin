package earth.worldwind.formats.archive

import java.sql.Connection
import java.sql.DriverManager

/**
 * JVM [SqliteBlobStore] over JDBC. The `sqlite-jdbc` driver is on the classpath transitively via
 * `geopackage-java`; it auto-registers through the JDBC 4 ServiceLoader (the `Class.forName` below is
 * just a belt-and-braces nudge for stripped classpaths).
 *
 * A single `Connection` is not safe for concurrent statements, so reads are serialized on [lock].
 * TODO(scale): a small read-connection pool (cf. GeoPackage `READ_HANDLE_COUNT`) for parallel reads.
 */
internal actual class SqliteBlobStore actual constructor(pathName: String) {
    private val lock = Any()
    private val connection: Connection = run {
        runCatching { Class.forName("org.sqlite.JDBC") }
        DriverManager.getConnection("jdbc:sqlite:$pathName")
    }

    actual fun blobForKey(key: String): ByteArray? = synchronized(lock) {
        connection.prepareStatement(
            "SELECT $MEDIA_CONTENT_COLUMN FROM $MEDIA_TABLE WHERE $MEDIA_KEY_COLUMN = ? LIMIT 1"
        ).use { st ->
            st.setString(1, key)
            st.executeQuery().use { rs -> if (rs.next()) rs.getBytes(1) else null }
        }
    }

    actual fun containsKey(key: String): Boolean = synchronized(lock) {
        connection.prepareStatement(
            "SELECT 1 FROM $MEDIA_TABLE WHERE $MEDIA_KEY_COLUMN = ? LIMIT 1"
        ).use { st ->
            st.setString(1, key)
            st.executeQuery().use { it.next() }
        }
    }

    actual fun rowCount(): Int = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $MEDIA_TABLE").use { if (it.next()) it.getInt(1) else 0 }
        }
    }

    actual fun close() { runCatching { connection.close() } }
}
