package earth.worldwind.formats.gpkg

import earth.worldwind.formats.gpkg.GeoPackage.Companion.FEATURE_PROPERTIES_COLUMN
import earth.worldwind.formats.gpkg.GeoPackage.Companion.FEATURE_UID_COLUMN
import earth.worldwind.render.image.ImageSource
import mil.nga.geopackage.BoundingBox
import mil.nga.geopackage.GeoPackage
import mil.nga.geopackage.GeoPackageCore
import mil.nga.geopackage.GeoPackageManager
import mil.nga.geopackage.extension.coverage.GriddedCoverageDataType
import mil.nga.geopackage.extension.nga.index.FeatureTableIndex
import mil.nga.geopackage.extension.rtree.RTreeIndexExtension
import mil.nga.geopackage.features.user.FeatureDao
import mil.nga.geopackage.features.user.FeatureRow
import mil.nga.geopackage.geom.GeoPackageGeometryData
import mil.nga.geopackage.tiles.user.TileTableMetadata
import mil.nga.sf.Geometry
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.roundToInt

actual typealias CoverageData<TImage> = mil.nga.geopackage.extension.coverage.CoverageData<TImage>
actual typealias StyleRow = mil.nga.geopackage.extension.nga.style.StyleRow
actual typealias IconRow = mil.nga.geopackage.extension.nga.style.IconRow
actual typealias FeatureStyle = mil.nga.geopackage.extension.nga.style.FeatureStyle

// JVM's GeoPackageManager opens a fresh connection per call, so a pool of read handles gives true
// concurrent feature reads.
internal actual val READ_HANDLE_COUNT = 4

actual fun openOrCreateGeoPackage(pathName: String, isReadOnly: Boolean): GeoPackageCore {
    var file = File(pathName)
    if (!isReadOnly && !file.exists()) file = GeoPackageManager.create(file, false)
    return GeoPackageManager.open(!isReadOnly, file).also {
        // WAL so the read-handle pool's read-only connections read concurrently with the writer
        // (mirrors Android's manager.isSqliteWriteAheadLogging). Persisted in the db header on first set.
        if (!isReadOnly) runCatching { it.database.execSQL("PRAGMA journal_mode=WAL") }
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

actual fun readCachedFeaturesWithProperties(
    geoPackage: GeoPackageCore, tableName: String,
): List<Pair<Geometry, String?>> {
    val gpkg = geoPackage as GeoPackage
    val plan = gpkg.featurePropertyPlan(tableName)
    return gpkg.getFeatureDao(tableName).queryForAll().mapNotNull { it.toGeomAndProps(plan) }
}

actual fun readFeaturesInBoundingBox(
    geoPackage: GeoPackageCore, tableName: String,
    minX: Double, minY: Double, maxX: Double, maxY: Double,
): List<Pair<Geometry, String?>> {
    val gpkg = geoPackage as GeoPackage
    val featureDao = gpkg.getFeatureDao(tableName)
    val plan = gpkg.featurePropertyPlan(tableName)
    return gpkg.mapFeaturesInBbox(featureDao, minX, minY, maxX, maxY) { it.toGeomAndProps(plan) }
}

/**
 * Query features whose geometry envelope intersects the bbox, resolving the spatial index the way
 * NGA's `FeatureIndexManager` does: prefer the OGC RTree if the table has it (own / QGIS-readable
 * caches), else fall back to the NGA Geometry Index Extension (e.g. an Android-authored cache,
 * whose SQLite can't host the RTree). [transform] is applied per row inside the open cursor.
 */
private inline fun <R : Any> GeoPackage.mapFeaturesInBbox(
    featureDao: FeatureDao, minX: Double, minY: Double, maxX: Double, maxY: Double,
    transform: (FeatureRow) -> R?,
): List<R> {
    val bbox = BoundingBox(minX, minY, maxX, maxY)
    val out = ArrayList<R>()
    if (RTreeIndexExtension(this).has(featureDao.table)) {
        val results = RTreeIndexExtension(this).getTableDao(featureDao).queryFeatures(bbox)
        try { val i = results.iterator(); while (i.hasNext()) transform(i.next())?.let(out::add) } finally { results.close() }
    } else {
        val results = FeatureTableIndex(this, featureDao).queryFeatures(bbox)
        try { val i = results.iterator(); while (i.hasNext()) transform(i.next())?.let(out::add) } finally { results.close() }
    }
    return out
}

/** WorldWind-private feature-cache columns, excluded when synthesizing properties from a foreign table. */
private val WW_FEATURE_PRIVATE_COLUMNS = setOf(FEATURE_UID_COLUMN, FEATURE_PROPERTIES_COLUMN)

/** `(hasFeaturePropertiesColumn, attributeColumnsToSerialize)` for [tableName]: a WorldWind cache
 *  reads its single feature_properties JSON column; a foreign table synthesizes properties from
 *  every real attribute column (skipping PK, geometry, and WW bookkeeping). Computed once per read. */
private fun GeoPackage.featurePropertyPlan(tableName: String): Pair<Boolean, List<String>> {
    val featureDao = getFeatureDao(tableName)
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
            if (++inserted % FEATURE_INSERT_BATCH == 0) featureDao.endAndBeginTransaction()
        }
        committed = true
    } finally {
        featureDao.endTransaction(committed)
    }
    // Build the Geometry Index in one pass after the inserts commit (a bulk store is replace-all,
    // so the table is the full set) — outside the feature write transaction, since the geometry-
    // index DAO uses its own connection and would deadlock (SQLITE_BUSY) against the open feature
    // write lock. The RTree was kept current by its triggers during the inserts.
    FeatureTableIndex(gpkg, featureDao).index(true)
}

/** Rows per write transaction for bulk feature inserts — see [insertCachedFeatures]. */
private const val FEATURE_INSERT_BATCH = 1000

actual fun truncateFeatureTable(geoPackage: GeoPackageCore, tableName: String) {
    (geoPackage as GeoPackage).getFeatureDao(tableName).deleteAll()
}

actual fun deleteFeatureTable(geoPackage: GeoPackageCore, tableName: String) {
    (geoPackage as GeoPackage).deleteTable(tableName)
}

/** No-op on JVM: the read path uses the OGC RTree extension per query and caches nothing per handle. */
actual fun releaseFeatureReadResources(geoPackage: GeoPackageCore) {}

actual fun createFeatureSpatialIndex(geoPackage: GeoPackageCore, tableName: String) {
    val gpkg = geoPackage as GeoPackage
    val featureDao = gpkg.getFeatureDao(tableName)
    // OGC RTree — the portable, QGIS-readable spatial index, kept current by its own SQL triggers.
    val rtree = RTreeIndexExtension(gpkg)
    if (!rtree.has(featureDao.table)) rtree.create(featureDao.table)
    // NGA Geometry Index Extension too, so platforms whose SQLite can't host the RTree (Android)
    // can read this cache. Maintained manually on each write (index(row) / deleteIndex).
    FeatureTableIndex(gpkg, featureDao).index()
}

/** No-op on JVM: reads go through the OGC RTree, which is created with the table and
 *  trigger-maintained — there is no extra read-side index to ensure. */
actual fun ensureFeatureReadIndexes(geoPackage: GeoPackageCore, tableName: String) {}

actual fun deleteCachedFeaturesByIds(geoPackage: GeoPackageCore, tableName: String, ids: Collection<Long>) {
    if (ids.isEmpty()) return
    val gpkg = geoPackage as GeoPackage
    val q = "\"${tableName.replace("\"", "\"\"")}\""
    // The OGC RTree stays consistent via its DELETE trigger; nga_geometry_index rows are left
    // dangling (they match no feature id, so queries ignore them) — the Android reconcile treats
    // nga rows as its rebuild source, and dangling ids fetch nothing, so this stays harmless.
    for (chunk in ids.chunked(500)) {
        gpkg.connection.execSQL(
            "DELETE FROM $q WHERE ${earth.worldwind.formats.gpkg.GeoPackage.FEATURE_ID_COLUMN} IN (${chunk.joinToString(",")})"
        )
    }
}

/** No-op on JVM: no per-file acceleration state (see the Android actual). */
actual fun releaseFeatureAcceleration(pathName: String) {}

actual fun writeFeatureTileFlat(
    geoPackage: GeoPackageCore, tableName: String,
    minX: Double, minY: Double, maxX: Double, maxY: Double,
    rows: List<GpkgFeatureRow>, upsertByUid: Boolean,
) {
    val gpkg = geoPackage as GeoPackage
    val featureDao = gpkg.getFeatureDao(tableName)
    val featureIndex = FeatureTableIndex(gpkg, featureDao)

    // Ids of the rows being replaced — bbox-replace (also the negative-cache clear) drops every
    // feature intersecting the tile; upsert drops the prior version of each feature by stable uid.
    val doomedIds: List<Long> = if (rows.isEmpty() || !upsertByUid) {
        gpkg.mapFeaturesInBbox(featureDao, minX, minY, maxX, maxY) { it.id }
    } else {
        val uids = rows.mapNotNull { it.uid }.toHashSet()
        if (uids.isEmpty()) emptyList() else buildList {
            for (chunk in uids.chunked(500)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val rs = featureDao.query("$FEATURE_UID_COLUMN IN ($placeholders)", chunk.toTypedArray())
                try { val i = rs.iterator(); while (i.hasNext()) add(i.next().id) } finally { rs.close() }
            }
        }
    }
    // Feature-table writes (deletes then inserts) — the RTree's triggers keep the RTree in sync as
    // rows change. The Geometry Index is synced afterwards, NOT here: its DAO uses a separate
    // connection and would deadlock (SQLITE_BUSY) against this feature write lock if interleaved.
    val insertedRows = ArrayList<FeatureRow>(rows.size)
    if (doomedIds.isNotEmpty() || rows.isNotEmpty()) {
        val srsId = featureDao.geometryColumns.srsId
        featureDao.beginTransaction()
        var committed = false
        try {
            for (id in doomedIds) featureDao.deleteById(id)
            for (row in rows) {
                val featureRow = featureDao.newRow()
                row.geometry?.let { featureRow.geometry = GeoPackageGeometryData.create(srsId, it) }
                row.properties?.let { featureRow.setValue(FEATURE_PROPERTIES_COLUMN, it) }
                row.uid?.let { featureRow.setValue(FEATURE_UID_COLUMN, it) }
                featureDao.insert(featureRow)
                insertedRows += featureRow
            }
            committed = true
        } finally {
            featureDao.endTransaction(committed)
        }
    }
    // Geometry Index sync, after the feature transaction has committed (see note above).
    for (id in doomedIds) featureIndex.deleteIndex(id)
    for (featureRow in insertedRows) featureIndex.index(featureRow)
}

actual fun featureIdsInBoundingBox(
    geoPackage: GeoPackageCore, tableName: String,
    minX: Double, minY: Double, maxX: Double, maxY: Double,
): List<Long> {
    val gpkg = geoPackage as GeoPackage
    return gpkg.mapFeaturesInBbox(gpkg.getFeatureDao(tableName), minX, minY, maxX, maxY) { it.id }
}

actual fun deleteVanishedOwnedFeatures(
    geoPackage: GeoPackageCore, tableName: String,
    minX: Double, minY: Double, maxX: Double, maxY: Double, keepUids: Set<String>,
) {
    if (keepUids.isEmpty()) return
    val gpkg = geoPackage as GeoPackage
    val featureDao = gpkg.getFeatureDao(tableName)
    val featureIndex = FeatureTableIndex(gpkg, featureDao)
    val doomedIds = gpkg.mapFeaturesInBbox(featureDao, minX, minY, maxX, maxY) {
        if (it.ownedAndVanished(keepUids, minX, minY, maxX, maxY)) it.id else null
    }
    if (doomedIds.isEmpty()) return
    // deleteById fires the RTree's triggers. Sync the Geometry Index after the feature deletes
    // commit — its DAO uses a separate connection and would deadlock against the write lock.
    featureDao.beginTransaction()
    var ok = false
    try {
        for (id in doomedIds) featureDao.deleteById(id)
        ok = true
    } finally {
        featureDao.endTransaction(ok)
    }
    for (id in doomedIds) featureIndex.deleteIndex(id)
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

actual fun buildImageSource(iconRow: IconRow) = ImageSource.fromImage(iconRow.dataImage.let { image ->
    val width = iconRow.width?.roundToInt() ?: image.width
    val height = iconRow.height?.roundToInt() ?: image.height
    if (width != image.width || height != image.height) {
        val scaledImage = image.getScaledInstance(width, height, Image.SCALE_SMOOTH)
        BufferedImage(width, height, image.type).apply {
            createGraphics().apply {
                drawImage(scaledImage, 0, 0, null)
                dispose()
            }
        }
    } else image
})