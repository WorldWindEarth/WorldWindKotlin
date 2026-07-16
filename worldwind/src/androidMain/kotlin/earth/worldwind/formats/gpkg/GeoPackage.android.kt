package earth.worldwind.formats.gpkg

import android.database.sqlite.SQLiteDatabase
import androidx.core.graphics.scale
import earth.worldwind.formats.gpkg.GeoPackage.Companion.FEATURE_ID_COLUMN
import earth.worldwind.formats.gpkg.GeoPackage.Companion.FEATURE_PROPERTIES_COLUMN
import earth.worldwind.formats.gpkg.GeoPackage.Companion.FEATURE_UID_COLUMN
import earth.worldwind.render.image.ImageSource
import mil.nga.geopackage.BoundingBox
import mil.nga.geopackage.GeoPackage
import mil.nga.geopackage.GeoPackageCore
import mil.nga.geopackage.GeoPackageFactory
import mil.nga.geopackage.db.GeoPackageConnection
import mil.nga.geopackage.db.GeoPackageDatabase
import mil.nga.geopackage.db.GeoPackageTableCreator
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

actual fun openOrCreateGeoPackage(pathName: String, isReadOnly: Boolean): GeoPackageCore {
    val manager = GeoPackageFactory.getManager(null)
    manager.isSqliteWriteAheadLogging = true
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
    return manager.openExternal(file, !isReadOnly)
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
// never be trusted-then-stale. Crash between the feature transaction and the rtree mirror is
// healed by the count reconcile in [ensureFeatureReadIndexes] on the next open.

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

private fun GeoPackage.rtreeDelete(tableName: String, ids: Collection<Long>) {
    if (ids.isEmpty()) return
    val db = rtreeDb()
    for (chunk in ids.chunked(500)) {
        db.execSQL("DELETE FROM ${quoted(rtreeName(tableName))} WHERE id IN (${chunk.joinToString(",")})")
    }
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
    // Keep NGA's Geometry Index Extension current as we insert (the OGC RTree's auto-triggers do
    // this on JVM; nga_geometry_index has none, so we call index(row) per inserted row).
    val featureIndex = FeatureTableIndex(gpkg, featureDao)
    // Chunked transactions instead of per-row autocommit: a Shapefile/WFS layer can be tens
    // of thousands of features, and a commit (fsync) per row makes the write minutes-long.
    // Chunking — rather than one big transaction — bounds how long the write lock is held so
    // concurrent render reads from the same GeoPackage aren't starved.
    val mirrored = ArrayList<Pair<Long, GeometryEnvelope?>>(rows.size)
    featureDao.beginTransaction()
    var committed = false
    try {
        var inserted = 0
        for ((geometry, propertiesJson) in rows) {
            val row = featureDao.newRow()
            row.geometry = GeoPackageGeometryData.create(featureDao.geometryColumns.srsId, geometry)
            propertiesJson?.let { row.setValue(FEATURE_PROPERTIES_COLUMN, it) }
            val id = featureDao.insert(row)
            featureIndex.index(row)
            mirrored.add(id to GeometryEnvelopeBuilder.buildEnvelope(geometry))
            if (++inserted % FEATURE_INSERT_BATCH == 0) featureDao.endAndBeginTransaction()
        }
        committed = true
    } finally {
        featureDao.endTransaction(committed)
    }
    gpkg.rtreeMirrorInserts(tableName, mirrored)
}

/** Apply queued rtree inserts after the feature transaction committed (WAL = one writer, so the
 *  rtree mirror runs as its own transaction on the bindings connection; a crash in between is
 *  healed by the count reconcile in [ensureFeatureReadIndexes]). */
private fun GeoPackage.rtreeMirrorInserts(tableName: String, rows: List<Pair<Long, GeometryEnvelope?>>) {
    if (rows.isEmpty() || !hasRtree(tableName)) return
    val db = rtreeDb()
    db.beginTransaction()
    try {
        for ((id, envelope) in rows) rtreeInsert(tableName, id, envelope)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

/** Rows per write transaction for bulk feature inserts — see [insertCachedFeatures]. */
private const val FEATURE_INSERT_BATCH = 1000

actual fun truncateFeatureTable(geoPackage: GeoPackageCore, tableName: String) {
    val gpkg = geoPackage as GeoPackage
    gpkg.getFeatureDao(tableName).deleteAll()
    if (gpkg.hasRtree(tableName)) gpkg.rtreeDb().execSQL("DELETE FROM ${quoted(rtreeName(tableName))}")
}

actual fun deleteFeatureTable(geoPackage: GeoPackageCore, tableName: String) {
    val gpkg = geoPackage as GeoPackage
    readPools[gpkg]?.remove(tableName) // pooled indices bound to the dropped table are now stale
    gpkg.deleteTable(tableName)
    if (gpkg.hasRtree(tableName)) {
        gpkg.rtreeDb().execSQL("DROP TABLE IF EXISTS ${quoted(rtreeName(tableName))}")
        rtreePresence.remove("${gpkg.path}|$tableName")
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
}

actual fun ensureFeatureReadIndexes(geoPackage: GeoPackageCore, tableName: String) {
    val gpkg = geoPackage as GeoPackage
    gpkg.createRtree(tableName)
    // Legacy read accelerator from before the rtree — the read path no longer scans
    // nga_geometry_index, so reclaim the space (vector caches are not in production; no fallback).
    gpkg.connection.execSQL("DROP INDEX IF EXISTS idx_ww_geometry_index_covering")
    // Count reconcile against the canonical nga_geometry_index: heals a crash between a feature
    // transaction and its rtree mirror, and backfills caches written by pre-rtree builds. Two
    // index-only COUNTs when consistent; one bulk INSERT..SELECT when not.
    val ngaCount = gpkg.connection.db.db.rawQuery(
        "SELECT COUNT(*) FROM nga_geometry_index WHERE table_name = ?", arrayOf(tableName),
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    val rtreeCount = gpkg.rtreeDb().rawQuery(
        "SELECT COUNT(*) FROM ${quoted(rtreeName(tableName))}", null,
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    if (ngaCount != rtreeCount) gpkg.rtreeRebuild(tableName)
}

actual fun writeFeatureTileFlat(
    geoPackage: GeoPackageCore, tableName: String,
    minX: Double, minY: Double, maxX: Double, maxY: Double,
    rows: List<GpkgFeatureRow>, upsertByUid: Boolean,
) {
    val gpkg = geoPackage as GeoPackage
    val featureDao = gpkg.getFeatureDao(tableName)
    val featureIndex = FeatureTableIndex(gpkg, featureDao)

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
    if (doomed.isNotEmpty()) {
        featureDao.beginTransaction()
        var ok = false
        try {
            for (row in doomed) {
                featureIndex.deleteIndex(row)
                featureDao.delete(row)
            }
            ok = true
        } finally {
            featureDao.endTransaction(ok)
        }
        gpkg.rtreeMirrorDeletes(tableName, doomed.map { it.id })
    }
    if (rows.isEmpty()) return

    val srsId = featureDao.geometryColumns.srsId
    // Keep NGA's Geometry Index Extension current as we insert (see insertCachedFeatures).
    val mirrored = ArrayList<Pair<Long, GeometryEnvelope?>>(rows.size)
    featureDao.beginTransaction()
    var committed = false
    try {
        var inserted = 0
        for (row in rows) {
            val featureRow = featureDao.newRow()
            row.geometry?.let { featureRow.geometry = GeoPackageGeometryData.create(srsId, it) }
            row.properties?.let { featureRow.setValue(FEATURE_PROPERTIES_COLUMN, it) }
            row.uid?.let { featureRow.setValue(FEATURE_UID_COLUMN, it) }
            val id = featureDao.insert(featureRow)
            featureIndex.index(featureRow)
            mirrored.add(id to row.geometry?.let { GeometryEnvelopeBuilder.buildEnvelope(it) })
            if (++inserted % FEATURE_INSERT_BATCH == 0) featureDao.endAndBeginTransaction()
        }
        committed = true
    } finally {
        featureDao.endTransaction(committed)
    }
    gpkg.rtreeMirrorInserts(tableName, mirrored)
}

private fun GeoPackage.rtreeMirrorDeletes(tableName: String, ids: Collection<Long>) {
    if (ids.isEmpty() || !hasRtree(tableName)) return
    rtreeDelete(tableName, ids)
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
    val q = quoted(tableName)
    for (chunk in ids.chunked(500)) {
        val inList = chunk.joinToString(",")
        gpkg.connection.execSQL("DELETE FROM $q WHERE $FEATURE_ID_COLUMN IN ($inList)")
        // Keep both manually-maintained indexes free of dangling rows (the rtree count reconcile
        // compares against nga_geometry_index, so they must stay in lockstep).
        gpkg.connection.db.db.execSQL(
            "DELETE FROM nga_geometry_index WHERE table_name = ? AND geom_id IN ($inList)", arrayOf(tableName),
        )
    }
    gpkg.rtreeMirrorDeletes(tableName, ids)
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
    val featureIndex = FeatureTableIndex(gpkg, featureDao)
    // Candidate rows whose envelope intersects the tile (rtree probe or NGA index); ownership is
    // then decided by the exact envelope-center check, so rtree over-approximation is harmless.
    val doomed = gpkg.queryFeatureRowsInBbox(featureDao, tableName, minX, minY, maxX, maxY)
        .filter { it.ownedAndVanished(keepUids, minX, minY, maxX, maxY) }
    if (doomed.isNotEmpty()) {
        featureDao.beginTransaction()
        var ok = false
        try {
            for (row in doomed) {
                featureIndex.deleteIndex(row)
                featureDao.delete(row)
            }
            ok = true
        } finally {
            featureDao.endTransaction(ok)
        }
        gpkg.rtreeMirrorDeletes(tableName, doomed.map { it.id })
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