package earth.worldwind.formats.gpkg

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import mil.nga.sf.Point
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.sqlite.database.sqlite.SQLiteDatabase as BindingsDatabase
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end device tests for the Android feature-cache rtree acceleration, through the REAL
 * production API (setupFeaturesContent / writeFeatureTile / readFeatureTile /
 * ensureFeatureReadIndexes) — not the raw actuals. Covers: rtree creation with the table, write
 * mirroring (insert, uid-upsert, vanish-deletes, bbox clear), reads probing the rtree, the count
 * reconcile healing a lost mirror, and removal of the legacy covering index.
 */
@RunWith(AndroidJUnit4::class)
class RtreeFeatureCacheDeviceTest {

    private lateinit var path: String
    private lateinit var gpkg: GeoPackage

    private val tableName = "rtree_cache_test"
    private val rtreeTable = "ww_rtree_$tableName"

    // The z15 tile containing the test coordinates: lon [30.4980, 30.5090], lat [50.3945, 50.4015].
    private val z = 15
    private val tx = 19160
    private val ty = 11056

    @Before
    fun setUp() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        path = File(dir, "rtree_cache_test.gpkg").absolutePath
        listOf(path, "$path-wal", "$path-shm").forEach { File(it).delete() }
        gpkg = GeoPackage(path, isReadOnly = false)
    }

    @After
    fun tearDown() {
        runCatching { gpkg.shutdown() }
        listOf(path, "$path-wal", "$path-shm").forEach { File(it).delete() }
    }

    /** Independent bindings connection for asserting on the rtree table from outside prod code. */
    private fun <R> withProbe(block: (BindingsDatabase) -> R): R {
        System.loadLibrary("sqliteX")
        return BindingsDatabase.openDatabase(path, null, BindingsDatabase.OPEN_READWRITE).use(block)
    }

    private fun rtreeCount(): Long = withProbe { db ->
        db.rawQuery("SELECT COUNT(*) FROM \"$rtreeTable\"", null).use { it.moveToFirst(); it.getLong(0) }
    }

    private fun row(uid: String, lon: Double, lat: Double) =
        GpkgFeatureRow(Point(lon, lat), """{"id":"$uid"}""", uid)

    @Test
    fun writeReadRoundTripMirrorsRtree() = runBlocking {
        val content = gpkg.setupFeaturesContent(tableName)

        gpkg.writeFeatureTile(content, z, tx, ty, listOf(
            row("way/1", 30.5000, 50.4000),
            row("way/2", 30.5005, 50.4005),
            row("way/3", 30.5010, 50.4010),
        ))
        assertEquals(3, rtreeCount(), "rtree mirror missed inserted rows")
        assertEquals(3, gpkg.readFeatureTile(content, z, tx, ty).size, "read did not return written features")

        // uid-upsert re-fetch reporting only two features: way/3 vanished upstream.
        gpkg.writeFeatureTile(content, z, tx, ty, listOf(
            row("way/1", 30.5000, 50.4000),
            row("way/2", 30.5005, 50.4005),
        ))
        assertEquals(2, gpkg.readFeatureTile(content, z, tx, ty).size, "vanished feature still served")
        assertEquals(2, rtreeCount(), "rtree mirror out of sync after uid-upsert + vanish delete")

        // Empty write = bbox clear + negative cache.
        gpkg.writeFeatureTile(content, z, tx, ty, emptyList())
        assertEquals(0, gpkg.readFeatureTile(content, z, tx, ty).size, "bbox clear left features behind")
        assertEquals(0, rtreeCount(), "rtree mirror out of sync after bbox clear")
    }

    @Test
    fun reconcileHealsLostMirrorAndDropsLegacyIndex() = runBlocking {
        val content = gpkg.setupFeaturesContent(tableName)
        gpkg.writeFeatureTile(content, z, tx, ty, listOf(
            row("way/1", 30.5000, 50.4000),
            row("way/2", 30.5005, 50.4005),
        ))
        assertEquals(2, rtreeCount())

        // Simulate a crash between the feature transaction and its rtree mirror. (Two steps: an
        // rtree vtable refuses modification while a cursor on itself is open — SQLITE_LOCKED_VTAB —
        // so a DELETE with a self-subquery would fail.)
        withProbe { db ->
            val victim = db.rawQuery("SELECT id FROM \"$rtreeTable\" LIMIT 1", null)
                .use { it.moveToFirst(); it.getLong(0) }
            db.execSQL("DELETE FROM \"$rtreeTable\" WHERE id = $victim")
        }
        assertEquals(1, rtreeCount())

        // Plant the legacy covering index a pre-rtree build would have left behind.
        gpkg.core.database.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_ww_geometry_index_covering " +
                "ON nga_geometry_index (table_name, min_x, max_x, min_y, max_y, geom_id)"
        )

        gpkg.ensureFeatureReadIndexes(content)

        assertEquals(2, rtreeCount(), "reconcile did not rebuild the rtree from nga_geometry_index")
        assertEquals(2, gpkg.readFeatureTile(content, z, tx, ty).size, "read broken after reconcile")
        val legacyPresent = withProbe { db ->
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = 'idx_ww_geometry_index_covering'", null,
            ).use { it.moveToFirst() }
        }
        assertFalse(legacyPresent, "legacy covering index not dropped")
    }

    @Test
    fun evictionKeepsIndexesInLockstep() = runBlocking {
        val content = gpkg.setupFeaturesContent(tableName)
        // Two coverage tiles' worth of features (neighbouring z15 tiles).
        gpkg.writeFeatureTile(content, z, tx, ty, listOf(row("way/1", 30.5000, 50.4000)))
        gpkg.writeFeatureTile(content, z, tx + 1, ty, listOf(row("way/2", 30.5120, 50.4000)))
        assertEquals(2, rtreeCount())

        // Cap at one coverage tile: the older tile's feature must vanish from ALL structures.
        gpkg.evictFeatures(content, earth.worldwind.layer.cache.CachePolicy(maxEntries = 1))
        val ngaCount = withProbe { db ->
            db.rawQuery(
                "SELECT COUNT(*) FROM nga_geometry_index WHERE table_name = ?", arrayOf(tableName),
            ).use { it.moveToFirst(); it.getLong(0) }
        }
        assertEquals(1, rtreeCount(), "rtree not mirrored on eviction")
        assertEquals(rtreeCount(), ngaCount, "nga_geometry_index and rtree out of lockstep after eviction")
    }

    @Test
    fun readsWorkAcrossReopen() = runBlocking {
        var content = gpkg.setupFeaturesContent(tableName)
        gpkg.writeFeatureTile(content, z, tx, ty, listOf(row("way/1", 30.5000, 50.4000)))
        gpkg.shutdown()

        gpkg = GeoPackage(path, isReadOnly = false)
        content = gpkg.setupFeaturesContent(tableName)
        gpkg.ensureFeatureReadIndexes(content)
        assertEquals(1, gpkg.readFeatureTile(content, z, tx, ty).size, "reopen lost the cached feature")
        assertTrue(rtreeCount() == 1L, "rtree missing after reopen")
    }
}
