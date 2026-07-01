package earth.worldwind.formats.archive

import earth.worldwind.layer.ogc3d.stream.ArchiveTileByteSource
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.sql.DriverManager
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** End-to-end test of the `.3dtiles` (SQLite) reader against a real on-disk `media(key, content)`
 *  database, plus the archive byte-source's authority-form URI routing. */
class Sqlite3dTilesArchiveTest {
    private lateinit var dbFile: File
    private val tilesetJson = """{"asset":{"version":"1.1"},"root":{}}""".encodeToByteArray()
    private val b3dm = byteArrayOf('b'.code.toByte(), '3'.code.toByte(), 'd'.code.toByte(), 'm'.code.toByte(), 1, 2, 3, 4)

    @BeforeTest fun setUp() {
        dbFile = File.createTempFile("sample", ".3dtiles")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            c.createStatement().use { it.executeUpdate("CREATE TABLE media (key TEXT PRIMARY KEY, content BLOB)") }
            c.prepareStatement("INSERT INTO media VALUES (?, ?)").use { st ->
                st.setString(1, "tileset.json"); st.setBytes(2, gzip(tilesetJson)); st.addBatch()   // stored gzipped
                st.setString(1, "content/0/0.b3dm"); st.setBytes(2, b3dm); st.addBatch()             // stored raw
                st.executeBatch()
            }
        }
    }

    @AfterTest fun tearDown() { dbFile.delete() }

    @Test fun readsEntriesAndInflatesGzip() = runBlocking {
        val archive = Sqlite3dTilesArchive.open(dbFile.absolutePath)
        try {
            assertEquals(2, archive.entryCount)
            assertTrue(archive.hasEntry("tileset.json"))
            assertTrue(archive.hasEntry("/tileset.json"))                        // leading slash normalized
            assertContentEquals(tilesetJson, archive.readEntry("tileset.json"))  // gunzipped transparently
            assertContentEquals(b3dm, archive.readEntry("content/0/0.b3dm"))     // raw passes through
            assertNull(archive.readEntry("missing.json"))
        } finally { archive.close() }
    }

    @Test fun byteSourceServesAuthorityFormUris() = runBlocking {
        val src = ArchiveTileByteSource(Sqlite3dTilesArchive.open(dbFile.absolutePath), ArchiveTileByteSource.THREEDTILES_SCHEME)
        try {
            val ok = src.get(ArchiveTileByteSource.rootUri("abc", "content/0/0.b3dm"))
            assertEquals(200, ok.statusCode)
            assertContentEquals(b3dm, ok.bytes)
            val miss = src.get(ArchiveTileByteSource.rootUri("abc", "nope.b3dm"))
            assertEquals(404, miss.statusCode)
        } finally { src.close() }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(bytes) }
        return bos.toByteArray()
    }
}
