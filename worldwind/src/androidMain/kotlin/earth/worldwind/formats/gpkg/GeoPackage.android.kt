package earth.worldwind.formats.gpkg

import android.database.sqlite.SQLiteDatabase
import androidx.core.graphics.scale
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
import java.io.File
import kotlin.math.roundToInt

actual typealias CoverageData<TImage> = mil.nga.geopackage.extension.coverage.CoverageData<TImage>
actual typealias StyleRow = mil.nga.geopackage.extension.nga.style.StyleRow
actual typealias IconRow = mil.nga.geopackage.extension.nga.style.IconRow
actual typealias FeatureStyle = mil.nga.geopackage.extension.nga.style.FeatureStyle

// No pool: GeoPackageFactory caches one handle per name (a pool would alias the same connection),
// and the framework SQLiteDatabase already pools connections under WAL (enabled below).
internal actual val READ_HANDLE_COUNT = 0

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
    return manager.openExternal(file, !isReadOnly).also { core ->
        // The framework pins wal_autocheckpoint to 100 pages (~400 KB), so tile-cache write bursts
        // checkpoint (WAL+db fsync) every few tiles. Restore the SQLite default, matching JVM.
        // PRAGMA is non-SELECT, so the compiled statement runs on the primary (writing) connection.
        if (!isReadOnly) runCatching {
            (core as GeoPackage).connection.db.db.compileStatement("PRAGMA wal_autocheckpoint=1000")
                .use { it.simpleQueryForLong() }
        }
    }
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
    // Borrow a pooled index (see readPools). queryFeatures issues the NGA Geometry Index Extension
    // (nga_geometry_index) query and returns the matching feature rows.
    return (geoPackage as GeoPackage).withReadIndex(tableName) { h ->
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
    featureDao.beginTransaction()
    var committed = false
    try {
        var inserted = 0
        for ((geometry, propertiesJson) in rows) {
            val row = featureDao.newRow()
            row.geometry = GeoPackageGeometryData.create(featureDao.geometryColumns.srsId, geometry)
            propertiesJson?.let { row.setValue(FEATURE_PROPERTIES_COLUMN, it) }
            featureDao.insert(row)
            featureIndex.index(row)
            if (++inserted % FEATURE_INSERT_BATCH == 0) featureDao.endAndBeginTransaction()
        }
        committed = true
    } finally {
        featureDao.endTransaction(committed)
    }
}

/** Rows per write transaction for bulk feature inserts — see [insertCachedFeatures]. */
private const val FEATURE_INSERT_BATCH = 1000

actual fun truncateFeatureTable(geoPackage: GeoPackageCore, tableName: String) {
    (geoPackage as GeoPackage).getFeatureDao(tableName).deleteAll()
}

actual fun deleteFeatureTable(geoPackage: GeoPackageCore, tableName: String) {
    val gpkg = geoPackage as GeoPackage
    readPools[gpkg]?.remove(tableName) // pooled indices bound to the dropped table are now stale
    gpkg.deleteTable(tableName)
}

actual fun createFeatureSpatialIndex(geoPackage: GeoPackageCore, tableName: String) {
    val gpkg = geoPackage as GeoPackage
    // The OGC RTree extension (used by the JVM actual) needs value-returning SQL functions
    // (ST_MinX, …) that Android's framework SQLite can't register. NGA's answer for this case is
    // the Geometry Index Extension (nga_geometry_index) — a registered, SQLite-version-agnostic
    // index. FeatureTableIndex.index() creates it and indexes existing rows; subsequent writes
    // keep it current via index(row) (the RTree's auto-triggers do that on JVM). Idempotent.
    FeatureTableIndex(gpkg, gpkg.getFeatureDao(tableName)).index()
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
    val doomed: List<FeatureRow> = if (rows.isEmpty() || !upsertByUid) {
        featureIndex.queryFeatures(BoundingBox(minX, minY, maxX, maxY)).use { it.toList() }
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
    }
    if (rows.isEmpty()) return

    val srsId = featureDao.geometryColumns.srsId
    // Keep NGA's Geometry Index Extension current as we insert (see insertCachedFeatures).
    featureDao.beginTransaction()
    var committed = false
    try {
        var inserted = 0
        for (row in rows) {
            val featureRow = featureDao.newRow()
            row.geometry?.let { featureRow.geometry = GeoPackageGeometryData.create(srsId, it) }
            row.properties?.let { featureRow.setValue(FEATURE_PROPERTIES_COLUMN, it) }
            row.uid?.let { featureRow.setValue(FEATURE_UID_COLUMN, it) }
            featureDao.insert(featureRow)
            featureIndex.index(featureRow)
            if (++inserted % FEATURE_INSERT_BATCH == 0) featureDao.endAndBeginTransaction()
        }
        committed = true
    } finally {
        featureDao.endTransaction(committed)
    }
}

actual fun featureIdsInBoundingBox(
    geoPackage: GeoPackageCore, tableName: String,
    minX: Double, minY: Double, maxX: Double, maxY: Double,
): List<Long> {
    // Borrow a pooled index (see readPools / readFeaturesInBoundingBox).
    return (geoPackage as GeoPackage).withReadIndex(tableName) { h ->
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
    // Candidates whose envelope intersects the tile, via the NGA Geometry Index Extension.
    val doomed = featureIndex.queryFeatures(BoundingBox(minX, minY, maxX, maxY)).use { cursor ->
        cursor.filter { it.ownedAndVanished(keepUids, minX, minY, maxX, maxY) }
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