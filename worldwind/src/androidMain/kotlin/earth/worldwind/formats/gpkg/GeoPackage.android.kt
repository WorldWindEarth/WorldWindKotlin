package earth.worldwind.formats.gpkg

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.core.graphics.scale
import earth.worldwind.formats.gpkg.GeoPackage.Companion.FEATURE_ID_COLUMN
import earth.worldwind.formats.gpkg.GeoPackage.Companion.FEATURE_PROPERTIES_COLUMN
import earth.worldwind.formats.gpkg.GeoPackage.Companion.FEATURE_UID_COLUMN
import earth.worldwind.render.image.ImageSource
import mil.nga.geopackage.BoundingBox
import mil.nga.geopackage.GeoPackage
import mil.nga.geopackage.GeoPackageCore
import mil.nga.geopackage.GeoPackageImpl
import mil.nga.geopackage.db.GeoPackageConnection
import mil.nga.geopackage.db.GeoPackageCursorFactory
import mil.nga.geopackage.db.GeoPackageDatabase
import mil.nga.geopackage.db.GeoPackageTableCreator
import mil.nga.geopackage.db.SQLiteDatabaseUtils
import mil.nga.geopackage.validate.GeoPackageValidate
import mil.nga.geopackage.extension.coverage.GriddedCoverageDataType
import mil.nga.geopackage.extension.nga.index.FeatureTableIndex
import mil.nga.geopackage.features.user.FeatureDao
import mil.nga.geopackage.features.user.FeatureRow
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import mil.nga.geopackage.geom.GeoPackageGeometryData
import mil.nga.geopackage.tiles.user.TileTableMetadata
import mil.nga.sf.Geometry
import mil.nga.sf.GeometryEnvelope
import mil.nga.sf.util.GeometryEnvelopeBuilder
import org.sqlite.database.sqlite.SQLiteDatabase as BindingsDatabase
import java.io.File
import kotlin.math.roundToInt

actual typealias CoverageData<TImage> = mil.nga.geopackage.extension.coverage.CoverageData<TImage>
actual typealias StyleRow = mil.nga.geopackage.extension.nga.style.StyleRow
actual typealias IconRow = mil.nga.geopackage.extension.nga.style.IconRow
actual typealias FeatureStyle = mil.nga.geopackage.extension.nga.style.FeatureStyle

// Pooled read-only handles, like the JVM actual. Each openExternal opens a fresh SQLiteDatabase
// (NGA's GeoPackageCreator calls SQLiteDatabaseUtils.openReadOnlyDatabase per call — no handle
// caching, so pool entries do NOT alias one connection; an earlier comment here assumed they
// would). The pool matters twice over on Android: it caps concurrent feature bbox scans at this
// count (each borrow suspends until a handle frees), and it keeps those scans off the shared
// writable handle, whose framework WAL connection pool (~4 connections) is what tile/elevation
// cache reads go through. Without it an OSM-buildings tile window (81 tiles) issued that many
// concurrent scans at once, monopolising the connection pool and Dispatchers.IO worker threads
// and stalling terrain + imagery tile loads for tens of seconds (simpleperf, 2026-07).
internal actual val READ_HANDLE_COUNT = 4

/** Reaches GeoPackageImpl's protected constructor for [openOrCreateGeoPackage]; the null Context
 *  matches what the context-less manager passed it before. */
private class ExternalGeoPackage(
    name: String, path: String, connection: GeoPackageConnection,
    cursorFactory: GeoPackageCursorFactory, writable: Boolean,
) : GeoPackageImpl(null, name, path, connection, cursorFactory, writable)

actual fun openOrCreateGeoPackage(pathName: String, isReadOnly: Boolean): GeoPackageCore {
    val file = File(pathName)
    if (!isReadOnly && !file.exists()) {
        // Create the new GeoPackage file manually due to manager.createFile requires Android Context
        GeoPackageConnection(GeoPackageDatabase(SQLiteDatabase.openOrCreateDatabase(file, null))).apply {
            setApplicationId()
            setUserVersion()
            GeoPackageTableCreator(this).createRequired()
            close()
        }
    }
    // NGA's manager.openExternal, replicated minus enableForeignKeys(): that call fronts PRAGMA
    // foreign_keys=ON with a full-database foreign_key_check — a row walk of every FK-bearing
    // table (nga_geometry_index alone is every indexed feature), 0.5-1s of cold pread64 per open
    // and per pooled read handle (simpleperf 2026-07), not skippable via manager flags. Setting
    // the pragma directly is the identical end state on a healthy file; with pre-existing
    // orphans NGA would merely have left enforcement off, and enforcement only vets rows being
    // written. Header/integrity validations already defaulted to off (context-less manager);
    // WAL matches the manager path's isSqliteWriteAheadLogging=true.
    val cursorFactory = GeoPackageCursorFactory()
    var writable = !isReadOnly
    val db = (if (writable) SQLiteDatabaseUtils.openReadWriteDatabaseAttempt(pathName, cursorFactory) else null)
        ?: SQLiteDatabaseUtils.openReadOnlyDatabase(pathName, cursorFactory).also { writable = false }
    db.enableWriteAheadLogging()
    val connection = GeoPackageConnection(GeoPackageDatabase(db, writable, cursorFactory))
    connection.foreignKeys(true)
    val core = ExternalGeoPackage(file.nameWithoutExtension, pathName, connection, cursorFactory, writable)
    GeoPackageValidate.validateMinimumTables(core)
    // The framework pins wal_autocheckpoint to 100 pages (~400 KB), so tile-cache write bursts
    // checkpoint (WAL+db fsync) every few tiles. Restore the SQLite default, matching JVM.
    // PRAGMA is non-SELECT, so the compiled statement runs on the primary (writing) connection.
    if (!isReadOnly) runCatching {
        connection.db.db.compileStatement("PRAGMA wal_autocheckpoint=1000")
            .use { it.simpleQueryForLong() }
    }
    return core
}

actual fun createCoverageData(
    geoPackage: GeoPackageCore, tableName: String, identifier: String?, contentsBoundingBox: BoundingBox?,
    contentsSrsId: Long, tileBoundingBox: BoundingBox?, tileSrsId: Long, isFloat: Boolean,
): CoverageData<*> = CoverageData.createTileTable(
    geoPackage as GeoPackage,
    TileTableMetadata.create(tableName, contentsBoundingBox, contentsSrsId, tileBoundingBox, tileSrsId).also {
        it.identifier = identifier
    },
    if (isFloat) GriddedCoverageDataType.FLOAT else GriddedCoverageDataType.INTEGER
)

// Per-handle, per-table NGA FeatureTableIndex cache. Building a FeatureTableIndex (and its FeatureDao)
// per read re-probes index/table metadata — a sqlite_master + nga_table_index query storm that
// accumulates native CursorWindows and leaks RSS to OOM under sustained panning. The index queries
// nga_geometry_index live, so it stays correct across feature writes; only a table DROP invalidates it.
private class FeatureHandle(val index: FeatureTableIndex, val dao: FeatureDao)
// Separate caches so the read index (used concurrently on Dispatchers.IO, guarded by synchronized) is
// never the same object the write index mutates. All writes run on the single-threaded writeDispatcher,
// so the write handle needs no extra locking; the two sets only share the underlying SQLite tables,
// which the framework SQLiteDatabase serializes under WAL.
// Bounded per-(handle, table) pool of read indices. Rebuilding a FeatureTableIndex per read re-probes
// table metadata (a sqlite_master/nga_table_index storm that leaked native CursorWindows → OOM); a
// SINGLE shared index would instead serialize every concurrent read on one monitor (heavy lock
// contention). A small pool gives both: reuse (no storm/leak) AND parallel reads — each borrows its
// own handle, no cross-thread lock. Overflow past the pool builds a transient handle that's GC'd.
private const val READ_INDEX_POOL = 8
private val readPools = ConcurrentHashMap<GeoPackage, ConcurrentHashMap<String, ArrayBlockingQueue<FeatureHandle>>>()

private fun GeoPackage.readPool(tableName: String): ArrayBlockingQueue<FeatureHandle> =
    readPools.getOrPut(this) { ConcurrentHashMap() }
        .getOrPut(tableName) { ArrayBlockingQueue(READ_INDEX_POOL) }

private inline fun <R> GeoPackage.withReadIndex(tableName: String, block: (FeatureHandle) -> R): R {
    val pool = readPool(tableName)
    val handle = pool.poll() ?: getFeatureDao(tableName).let { FeatureHandle(FeatureTableIndex(this, it), it) }
    try { return block(handle) } finally { pool.offer(handle) } // offer drops on overflow (extra GC'd)
}

/** Drop all pooled read indices for a handle being closed (else the static pool pins the closed
 *  GeoPackage + its DAOs across open/close cycles). Called from [GeoPackage.shutdown]. */
actual fun releaseFeatureReadResources(geoPackage: GeoPackageCore) {
    readPools.remove(geoPackage as GeoPackage)
}

// --- Feature rtree acceleration ------------------------------------------------------------
//
// Framework SQLite has no rtree module and can't register the value-returning ST_* functions the
// OGC RTree triggers call — NGA's own RTreeIndexExtension.create throws unconditionally on
// Android (pinned by RTreeBindingsSpikeTest). nga_geometry_index alone answers a 2D bbox query
// with a 1D range scan over ~half the table's rows per tile read (pread64-dominated, seconds per
// tile at region scale — simpleperf 2026-07). So Android keeps nga_geometry_index as the
// canonical, portable index (maintained exactly as NGA prescribes) and mirrors its rows into a
// WorldWind-owned rtree virtual table per feature table — ww_rtree_<table>(id, minx, maxx, miny,
// maxy) — served by the SQLite R*Tree module of the NGA SQLite Android Bindings (libsqliteX, a
// transitive dependency of geopackage-android). Reads probe the rtree (O(log n + matches), ~30x
// the covering-index scan at 50k rows on device) and fetch feature rows by id.
//
// Deliberately NOT the OGC rtree_<table>_<geom> / gpkg_rtree_index extension: that registration
// promises trigger maintenance, which no SQLite available to us can provide — an external tool
// trusting it after an external edit would silently return wrong results. An unregistered
// ww_-scoped vtable is ignored by external GIS (they discover layers via gpkg_contents) and can
// never be trusted-then-stale.
//
// Consistency: the bindings connection sees the whole file, so every feature WRITE runs all
// three mutations — feature row, nga_geometry_index, rtree — in ONE transaction on it
// ([inAtomicFeatureTransaction]): a crash commits all or none. Reads stay on the framework
// handles (WAL keeps committed bindings writes visible) and off libsqliteX. NGA's DAO write path
// can't join that transaction: GeoPackageConnection hardcodes ORMLite's AndroidConnectionSource
// onto the framework handle.

/** One owned bindings connection per database file, opened with WAL preserved. NEVER use NGA's
 *  lazy GeoPackageDatabase.getBindingsDb(): its open path DOWNGRADES the file to
 *  journal_mode=delete (pinned by RTreeBindingsSpikeTest), breaking every concurrent reader. */
private val rtreeConnections = ConcurrentHashMap<String, BindingsDatabase>()

/** Per "path|table" memo of whether ww_rtree_<table> exists, saving a sqlite_master probe per read. */
private val rtreePresence = ConcurrentHashMap<String, Boolean>()

private fun rtreeName(tableName: String) = "ww_rtree_$tableName"
private fun quoted(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

private fun openRtreeConnection(pathName: String): BindingsDatabase {
    System.loadLibrary("sqliteX")
    return try {
        BindingsDatabase.openDatabase(
            pathName, null,
            BindingsDatabase.OPEN_READWRITE or BindingsDatabase.ENABLE_WRITE_AHEAD_LOGGING,
        )
    } catch (e: Throwable) {
        // Read-only file (user gpkg replay). The rtree, if present, is still readable.
        BindingsDatabase.openDatabase(pathName, null, BindingsDatabase.OPEN_READONLY)
    }
}

private fun GeoPackage.rtreeDb(): BindingsDatabase =
    rtreeConnections.computeIfAbsent(path) { openRtreeConnection(it) }

private fun GeoPackage.hasRtree(tableName: String): Boolean =
    rtreePresence.computeIfAbsent("$path|$tableName") { _ ->
        rtreeDb().rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(rtreeName(tableName)),
        ).use { it.moveToFirst() }
    }

private fun GeoPackage.createRtree(tableName: String) {
    rtreeDb().execSQL(
        "CREATE VIRTUAL TABLE IF NOT EXISTS ${quoted(rtreeName(tableName))} USING rtree(id, minx, maxx, miny, maxy)"
    )
    rtreePresence["$path|$tableName"] = true
}

/** Mirror one feature's envelope. INSERT OR REPLACE keeps a re-run (reconcile races, retried
 *  writes) idempotent. No-op for null envelopes — matching FeatureTableIndex.index(row). */
private fun GeoPackage.rtreeInsert(tableName: String, id: Long, envelope: GeometryEnvelope?) {
    envelope ?: return
    rtreeDb().execSQL(
        "INSERT OR REPLACE INTO ${quoted(rtreeName(tableName))} VALUES (?, ?, ?, ?, ?)",
        arrayOf<Any>(id, envelope.minX, envelope.maxX, envelope.minY, envelope.maxY),
    )
}

/** Ids whose stored envelope intersects the bbox. The rtree stores float32 bounds rounded
 *  OUTWARD, so this is a superset of the exact envelope matches (never misses) — callers
 *  needing exact envelope semantics filter the fetched rows (see [readFeaturesInBoundingBox]). */
private fun GeoPackage.rtreeProbe(
    tableName: String, minX: Double, minY: Double, maxX: Double, maxY: Double,
): List<Long> = rtreeDb().rawQuery(
    "SELECT id FROM ${quoted(rtreeName(tableName))} WHERE minx <= ? AND maxx >= ? AND miny <= ? AND maxy >= ?",
    arrayOf(maxX.toString(), minX.toString(), maxY.toString(), minY.toString()),
).use { cursor ->
    buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
}

/** Rebuild the rtree from the canonical nga_geometry_index rows — the crash-recovery and
 *  existing-cache-migration path. One bulk statement; both tables live in the same file, so the
 *  bindings connection reads the source directly. */
private fun GeoPackage.rtreeRebuild(tableName: String) {
    val db = rtreeDb()
    db.beginTransaction()
    try {
        db.execSQL("DELETE FROM ${quoted(rtreeName(tableName))}")
        db.execSQL(
            "INSERT INTO ${quoted(rtreeName(tableName))} " +
                "SELECT geom_id, min_x, max_x, min_y, max_y FROM nga_geometry_index WHERE table_name = ?",
            arrayOf(tableName),
        )
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

// --- Write-path generation stamp ------------------------------------------------------------
//
// Generation 2 = atomic writes, so a clean attach has nothing to reconcile. The stamp exists to
// heal caches from generation-1 builds, whose two-connection writes could die between the
// feature transaction and its rtree mirror: a missing or older stamp triggers one count
// reconcile, then every later attach is O(1). The unconditional reconcile this replaces scanned
// the table's whole nga_geometry_index PK range from cold flash on every open (~0.7s at region
// scale, simpleperf 2026-07), starving tile reads and delaying first map imagery behind the
// symbols layer. Unregistered private table, invisible to external GIS like ww_rtree_*.

private const val RTREE_GENERATION_TABLE = "ww_rtree_generation"

/** Feature writes mutate feature row, nga_geometry_index and rtree in one transaction. */
private const val RTREE_GENERATION_ATOMIC = 2L

/** Failure is harmless — an unstamped table just reconciles once more on the next attach. */
private fun GeoPackage.stampRtreeGeneration(tableName: String) {
    runCatching {
        val db = rtreeDb()
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $RTREE_GENERATION_TABLE (table_name TEXT PRIMARY KEY, generation INTEGER NOT NULL)"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO $RTREE_GENERATION_TABLE VALUES (?, $RTREE_GENERATION_ATOMIC)",
            arrayOf<Any>(tableName),
        )
    }
}

/** Unknown state (no table, no row, unreadable) reads as generation 0 — reconcile is always safe. */
private fun GeoPackage.rtreeGeneration(tableName: String): Long = runCatching {
    rtreeDb().rawQuery(
        "SELECT generation FROM $RTREE_GENERATION_TABLE WHERE table_name = ?", arrayOf(tableName),
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
}.getOrDefault(0L)

// --- Atomic feature write engine ------------------------------------------------------------

/** One bindings-connection transaction across feature rows, nga_geometry_index and the rtree —
 *  a crash commits all three or none. writeDispatcher serializes all writers. */
private inline fun <R> GeoPackage.inAtomicFeatureTransaction(block: (BindingsDatabase) -> R): R {
    val db = rtreeDb()
    db.beginTransaction()
    try {
        val result = block(db)
        db.setTransactionSuccessful()
        return result
    } finally {
        db.endTransaction()
    }
}

/** Commit and reopen the transaction mid-bulk-insert, bounding write-lock hold time. */
private fun BindingsDatabase.commitAndContinue() {
    setTransactionSuccessful()
    endTransaction()
    beginTransaction()
}

/** Insert one feature row. Only the columns we carry values for are written — older cache tables
 *  differ in optional columns (e.g. last_modified), and defaults cover the rest. */
private fun BindingsDatabase.insertFeatureRow(
    tableName: String, geometryColumn: String, srsId: Long,
    geometry: Geometry?, properties: String?, uid: String?,
): Long {
    val values = ContentValues()
    geometry?.let { values.put(geometryColumn, GeoPackageGeometryData.create(srsId, it).toBytes()) }
    properties?.let { values.put(FEATURE_PROPERTIES_COLUMN, it) }
    uid?.let { values.put(FEATURE_UID_COLUMN, it) }
    return insertOrThrow(quoted(tableName), geometryColumn, values)
}

/** Maintain NGA's Geometry Index Extension exactly as FeatureTableIndex.index(row) would — it
 *  stays the canonical, portable index. No-op for null envelopes, matching NGA and the rtree. */
private fun BindingsDatabase.upsertGeometryIndex(tableName: String, geomId: Long, envelope: GeometryEnvelope?) {
    envelope ?: return
    execSQL(
        "INSERT OR REPLACE INTO nga_geometry_index " +
            "(table_name, geom_id, min_x, max_x, min_y, max_y, min_z, max_z, min_m, max_m) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        arrayOf(
            tableName, geomId, envelope.minX, envelope.maxX, envelope.minY, envelope.maxY,
            envelope.minZ, envelope.maxZ, envelope.minM, envelope.maxM,
        ),
    )
}

/** Remove rows from all three structures. Caller runs inside [inAtomicFeatureTransaction]. */
private fun GeoPackage.deleteFeatureTriple(
    db: BindingsDatabase, tableName: String, idColumn: String, ids: Collection<Long>,
) {
    if (ids.isEmpty()) return
    val mirror = hasRtree(tableName)
    for (chunk in ids.chunked(500)) {
        val inList = chunk.joinToString(",")
        db.execSQL("DELETE FROM ${quoted(tableName)} WHERE ${quoted(idColumn)} IN ($inList)")
        db.execSQL(
            "DELETE FROM nga_geometry_index WHERE table_name = ? AND geom_id IN ($inList)", arrayOf<Any>(tableName),
        )
        if (mirror) db.execSQL("DELETE FROM ${quoted(rtreeName(tableName))} WHERE id IN ($inList)")
    }
}

/** Close the owned bindings connection (and presence memos) for one database file. Called once
 *  from [earth.worldwind.formats.gpkg.GeoPackage.shutdown]. */
actual fun releaseFeatureAcceleration(pathName: String) {
    rtreeConnections.remove(pathName)?.let { runCatching { it.close() } }
    rtreePresence.keys.removeAll { it.startsWith("$pathName|") }
}

actual fun readCachedFeaturesWithProperties(
    geoPackage: GeoPackageCore, tableName: String,
): List<Pair<Geometry, String?>> {
    val featureDao = (geoPackage as GeoPackage).getFeatureDao(tableName)
    val plan = featurePropertyPlan(featureDao)
    return featureDao.queryForAll().use { cursor ->
        cursor.mapNotNull { it.toGeomAndProps(plan) }
    }
}

actual fun readFeaturesInBoundingBox(
    geoPackage: GeoPackageCore, tableName: String,
    minX: Double, minY: Double, maxX: Double, maxY: Double,
): List<Pair<Geometry, String?>> {
    val gpkg = geoPackage as GeoPackage
    if (gpkg.hasRtree(tableName)) {
        // Fast path: rtree probe (float32 over-approximate — never misses) + fetch rows by id
        // through a borrowed dao handle, then exact envelope filter to preserve the NGA-equivalent
        // result set. The rtree query runs on the owned bindings connection, so it never competes
        // with tile/elevation reads for the framework handles.
        val ids = gpkg.rtreeProbe(tableName, minX, minY, maxX, maxY)
        if (ids.isEmpty()) return emptyList()
        return gpkg.withReadIndex(tableName) { h ->
            val plan = featurePropertyPlan(h.dao)
            val pk = h.dao.table.pkColumn?.name ?: "id"
            buildList {
                for (chunk in ids.chunked(500)) {
                    h.dao.query("$pk IN (${chunk.joinToString(",")})", emptyArray()).use { cursor ->
                        for (row in cursor) {
                            if (!row.envelopeIntersects(minX, minY, maxX, maxY)) continue
                            row.toGeomAndProps(plan)?.let { add(it) }
                        }
                    }
                }
            }
        }
    }
    // Foreign files / read-only opens of tables without the rtree: the NGA Geometry Index query,
    // via a pooled index handle (see readPools).
    return gpkg.withReadIndex(tableName) { h ->
        val plan = featurePropertyPlan(h.dao)
        h.index.queryFeatures(BoundingBox(minX, minY, maxX, maxY))
            .use { cursor -> cursor.mapNotNull { it.toGeomAndProps(plan) } }
    }
}

/** WorldWind-private feature-cache columns, excluded when synthesizing properties from a foreign table. */
private val WW_FEATURE_PRIVATE_COLUMNS = setOf(FEATURE_UID_COLUMN, FEATURE_PROPERTIES_COLUMN)

/** See the jvm actual's [featurePropertyPlan]: WorldWind cache vs foreign-table property handling. */
private fun featurePropertyPlan(featureDao: FeatureDao): Pair<Boolean, List<String>> {
    val table = featureDao.table
    val hasWwProperties = runCatching { table.getColumnIndex(FEATURE_PROPERTIES_COLUMN) }.isSuccess
    val attrColumns = if (hasWwProperties) emptyList() else table.columnNames.filter {
        it != featureDao.geometryColumns.columnName && it != table.pkColumn?.name &&
            it !in WW_FEATURE_PRIVATE_COLUMNS
    }
    return hasWwProperties to attrColumns
}

private fun FeatureRow.toGeomAndProps(plan: Pair<Boolean, List<String>>): Pair<Geometry, String?>? {
    val geom = getGeometry()?.geometry?.takeIf { !it.isEmpty } ?: return null
    val (hasWwProperties, attrColumns) = plan
    val properties =
        if (hasWwProperties) getValue(FEATURE_PROPERTIES_COLUMN) as? String
        else foreignFeaturePropertiesJson(attrColumns.associateWith { runCatching { getValue(it) }.getOrNull() })
    return geom to properties
}

actual fun insertCachedFeatures(
    geoPackage: GeoPackageCore, tableName: String, rows: List<Pair<Geometry, String?>>,
) {
    if (rows.isEmpty()) return
    val gpkg = geoPackage as GeoPackage
    val featureDao = gpkg.getFeatureDao(tableName)
    val geometryColumn = featureDao.geometryColumns.columnName
    val srsId = featureDao.geometryColumns.srsId
    val mirror = gpkg.hasRtree(tableName)
    // Chunked transactions: a commit (fsync) per row makes a Shapefile/WFS bulk write
    // minutes-long, while one big transaction starves concurrent render reads of the write lock.
    // Each chunk is atomic across all three structures, so a crash between chunks loses tail
    // rows but never diverges the indexes.
    gpkg.inAtomicFeatureTransaction { db ->
        var inserted = 0
        for ((geometry, propertiesJson) in rows) {
            val id = db.insertFeatureRow(tableName, geometryColumn, srsId, geometry, propertiesJson, uid = null)
            val envelope = GeometryEnvelopeBuilder.buildEnvelope(geometry)
            db.upsertGeometryIndex(tableName, id, envelope)
            if (mirror) gpkg.rtreeInsert(tableName, id, envelope)
            if (++inserted % FEATURE_INSERT_BATCH == 0) db.commitAndContinue()
        }
    }
}

/** Rows per write transaction for bulk feature inserts — see [insertCachedFeatures]. */
private const val FEATURE_INSERT_BATCH = 1000

actual fun truncateFeatureTable(geoPackage: GeoPackageCore, tableName: String) {
    val gpkg = geoPackage as GeoPackage
    // All three structures in one transaction. The DAO-based predecessor left nga_geometry_index
    // populated, so the count reconcile resurrected the deleted ids into the rtree on next open.
    gpkg.inAtomicFeatureTransaction { db ->
        db.execSQL("DELETE FROM ${quoted(tableName)}")
        db.execSQL("DELETE FROM nga_geometry_index WHERE table_name = ?", arrayOf<Any>(tableName))
        if (gpkg.hasRtree(tableName)) db.execSQL("DELETE FROM ${quoted(rtreeName(tableName))}")
    }
}

actual fun deleteFeatureTable(geoPackage: GeoPackageCore, tableName: String) {
    val gpkg = geoPackage as GeoPackage
    readPools[gpkg]?.remove(tableName) // pooled indices bound to the dropped table are now stale
    gpkg.deleteTable(tableName)
    if (gpkg.hasRtree(tableName)) {
        gpkg.rtreeDb().execSQL("DROP TABLE IF EXISTS ${quoted(rtreeName(tableName))}")
        rtreePresence.remove("${gpkg.path}|$tableName")
        runCatching {
            gpkg.rtreeDb().execSQL("DELETE FROM $RTREE_GENERATION_TABLE WHERE table_name = ?", arrayOf<Any>(tableName))
        }
    }
}

actual fun createFeatureSpatialIndex(geoPackage: GeoPackageCore, tableName: String) {
    val gpkg = geoPackage as GeoPackage
    // The trigger-based OGC RTree extension (used by the JVM actual) is unsupported on Android —
    // NGA's RTreeIndexExtension.create throws unconditionally (no SQLite here can host the ST_*
    // functions its triggers call). So: NGA's Geometry Index Extension (nga_geometry_index) as the
    // registered, portable index — FeatureTableIndex.index() creates it and indexes existing rows;
    // writes keep it current via index(row) — plus the WorldWind rtree acceleration vtable that
    // the read path probes. Both idempotent.
    FeatureTableIndex(gpkg, gpkg.getFeatureDao(tableName)).index()
    gpkg.createRtree(tableName)
    // Fresh, consistent pair with atomic writes ahead — stamp so the first attach skips the reconcile.
    gpkg.stampRtreeGeneration(tableName)
}

actual fun ensureFeatureReadIndexes(geoPackage: GeoPackageCore, tableName: String) {
    val gpkg = geoPackage as GeoPackage
    if (!gpkg.hasRtree(tableName)) {
        // First open of a cache written by a pre-rtree build: create the rtree, backfill it from
        // the canonical nga_geometry_index, and reclaim the legacy covering index only such
        // builds carry (every rtree-era open has already dropped it). One-time migration cost.
        gpkg.createRtree(tableName)
        gpkg.connection.execSQL("DROP INDEX IF EXISTS idx_ww_geometry_index_covering")
        gpkg.rtreeRebuild(tableName)
        gpkg.stampRtreeGeneration(tableName)
        return
    }
    // Atomic-generation caches cannot diverge — attach is a memoized presence probe plus one
    // single-row read, all on the bindings connection, nothing on the framework pool. Older
    // generations reconcile once (see the generation stamp comment), then are stamped.
    if (gpkg.rtreeGeneration(tableName) >= RTREE_GENERATION_ATOMIC) return
    val ngaCount = gpkg.connection.db.db.rawQuery(
        "SELECT COUNT(*) FROM nga_geometry_index WHERE table_name = ?", arrayOf(tableName),
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    val rtreeCount = gpkg.rtreeDb().rawQuery(
        "SELECT COUNT(*) FROM ${quoted(rtreeName(tableName))}", null,
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    if (ngaCount != rtreeCount) gpkg.rtreeRebuild(tableName)
    gpkg.stampRtreeGeneration(tableName)
}

actual fun writeFeatureTileFlat(
    geoPackage: GeoPackageCore, tableName: String,
    minX: Double, minY: Double, maxX: Double, maxY: Double,
    rows: List<GpkgFeatureRow>, upsertByUid: Boolean,
) {
    val gpkg = geoPackage as GeoPackage
    val featureDao = gpkg.getFeatureDao(tableName)

    // Remove the rows being replaced — bbox-replace (also the negative-cache clear) drops every
    // feature intersecting the tile; upsert drops the prior version of each feature by stable uid.
    // Materialize first (cursor closed) so we don't delete under an open cursor, then drop each
    // feature row AND its Geometry Index Extension entry so the index never leaks dangling rows.
    // The rtree candidate set is float32-over-approximate, so bbox-replace re-checks the exact
    // envelope — otherwise a neighbour tile's edge feature (~1e-6° outside) would be deleted here
    // and not re-inserted by this tile's rows.
    val doomed: List<FeatureRow> = if (rows.isEmpty() || !upsertByUid) {
        gpkg.queryFeatureRowsInBbox(featureDao, tableName, minX, minY, maxX, maxY)
            .filter { it.envelopeIntersects(minX, minY, maxX, maxY) }
    } else {
        val uids = rows.mapNotNull { it.uid }.toHashSet()
        if (uids.isEmpty()) emptyList() else buildList {
            for (chunk in uids.chunked(500)) {
                val placeholders = chunk.joinToString(",") { "?" }
                featureDao.query("$FEATURE_UID_COLUMN IN ($placeholders)", chunk.toTypedArray())
                    .use { cursor -> addAll(cursor) }
            }
        }
    }
    if (doomed.isEmpty() && rows.isEmpty()) return

    // One transaction per tile write: replace-then-insert commits as a unit, so a crash leaves
    // every structure as before and the tile is simply re-fetched. No chunking at tile sizes.
    val idColumn = featureDao.table.pkColumn?.name ?: FEATURE_ID_COLUMN
    val geometryColumn = featureDao.geometryColumns.columnName
    val srsId = featureDao.geometryColumns.srsId
    val mirror = gpkg.hasRtree(tableName)
    gpkg.inAtomicFeatureTransaction { db ->
        gpkg.deleteFeatureTriple(db, tableName, idColumn, doomed.map { it.id })
        for (row in rows) {
            val id = db.insertFeatureRow(tableName, geometryColumn, srsId, row.geometry, row.properties, row.uid)
            val envelope = row.geometry?.let { GeometryEnvelopeBuilder.buildEnvelope(it) }
            db.upsertGeometryIndex(tableName, id, envelope)
            if (mirror) gpkg.rtreeInsert(tableName, id, envelope)
        }
    }
}

/** Feature rows whose envelope MAY intersect the bbox: rtree probe + fetch-by-id when the rtree
 *  exists, else the NGA Geometry Index query (foreign files and read-only opens of caches that
 *  never had the rtree). Both are envelope-over-approximate; callers needing exact envelope
 *  semantics filter via [envelopeIntersects]. */
private fun GeoPackage.queryFeatureRowsInBbox(
    featureDao: FeatureDao, tableName: String,
    minX: Double, minY: Double, maxX: Double, maxY: Double,
): List<FeatureRow> = if (hasRtree(tableName)) {
    val ids = rtreeProbe(tableName, minX, minY, maxX, maxY)
    val pk = featureDao.table.pkColumn?.name ?: "id"
    buildList {
        for (chunk in ids.chunked(500)) {
            featureDao.query("$pk IN (${chunk.joinToString(",")})", emptyArray())
                .use { cursor -> addAll(cursor) }
        }
    }
} else {
    FeatureTableIndex(this, featureDao).queryFeatures(BoundingBox(minX, minY, maxX, maxY)).use { it.toList() }
}

private fun FeatureRow.envelopeIntersects(minX: Double, minY: Double, maxX: Double, maxY: Double): Boolean {
    val env = geometry?.getOrBuildEnvelope() ?: return false
    return env.minX <= maxX && env.maxX >= minX && env.minY <= maxY && env.maxY >= minY
}

actual fun deleteCachedFeaturesByIds(geoPackage: GeoPackageCore, tableName: String, ids: Collection<Long>) {
    if (ids.isEmpty()) return
    val gpkg = geoPackage as GeoPackage
    gpkg.inAtomicFeatureTransaction { db ->
        gpkg.deleteFeatureTriple(db, tableName, FEATURE_ID_COLUMN, ids)
    }
}

actual fun featureIdsInBoundingBox(
    geoPackage: GeoPackageCore, tableName: String,
    minX: Double, minY: Double, maxX: Double, maxY: Double,
): List<Long> {
    val gpkg = geoPackage as GeoPackage
    // The rtree id set is float32 over-approximate (a feature ~1e-6° outside may be included).
    // The one caller — coverage-tile eviction — treats ids as membership hints for keep/evict
    // sets, where over-approximation only biases toward KEEPING features: safe, no exact filter.
    if (gpkg.hasRtree(tableName)) return gpkg.rtreeProbe(tableName, minX, minY, maxX, maxY)
    return gpkg.withReadIndex(tableName) { h ->
        h.index.queryFeatures(BoundingBox(minX, minY, maxX, maxY)).use { cursor -> cursor.map { it.id } }
    }
}

actual fun deleteVanishedOwnedFeatures(
    geoPackage: GeoPackageCore, tableName: String,
    minX: Double, minY: Double, maxX: Double, maxY: Double, keepUids: Set<String>,
) {
    if (keepUids.isEmpty()) return
    val gpkg = geoPackage as GeoPackage
    val featureDao = gpkg.getFeatureDao(tableName)
    // Candidate rows whose envelope intersects the tile (rtree probe or NGA index); ownership is
    // then decided by the exact envelope-center check, so rtree over-approximation is harmless.
    val doomed = gpkg.queryFeatureRowsInBbox(featureDao, tableName, minX, minY, maxX, maxY)
        .filter { it.ownedAndVanished(keepUids, minX, minY, maxX, maxY) }
    if (doomed.isNotEmpty()) {
        val idColumn = featureDao.table.pkColumn?.name ?: FEATURE_ID_COLUMN
        gpkg.inAtomicFeatureTransaction { db ->
            gpkg.deleteFeatureTriple(db, tableName, idColumn, doomed.map { it.id })
        }
    }
}

/** Uid-keyed, not in [keepUids], and geometry envelope-center inside the tile bbox — i.e. it belongs
 *  to this tile and vanished upstream. Center formula matches CachedGeometry.envelopeCenter. */
private fun FeatureRow.ownedAndVanished(
    keepUids: Set<String>, minX: Double, minY: Double, maxX: Double, maxY: Double,
): Boolean {
    val uid = getValue(FEATURE_UID_COLUMN) as? String ?: return false
    if (uid in keepUids) return false
    val env = geometry?.getOrBuildEnvelope() ?: return false
    val cx = (env.minX + env.maxX) / 2.0
    val cy = (env.minY + env.maxY) / 2.0
    return cx >= minX && cx < maxX && cy >= minY && cy < maxY
}

actual fun buildImageSource(iconRow: IconRow) = ImageSource.fromBitmap(iconRow.dataBitmap.let { bitmap ->
    val width = iconRow.width?.roundToInt() ?: bitmap.width
    val height = iconRow.height?.roundToInt() ?: bitmap.height
    bitmap.scale(width, height)
})