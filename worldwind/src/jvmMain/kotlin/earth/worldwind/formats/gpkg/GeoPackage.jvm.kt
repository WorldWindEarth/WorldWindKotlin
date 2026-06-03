package earth.worldwind.formats.gpkg

import earth.worldwind.formats.gpkg.GeoPackage.Companion.FEATURE_PROPERTIES_COLUMN
import earth.worldwind.formats.gpkg.GeoPackage.Companion.LAST_MODIFIED_COLUMN
import earth.worldwind.formats.gpkg.GeoPackage.Companion.TILE_X_COLUMN
import earth.worldwind.formats.gpkg.GeoPackage.Companion.TILE_Y_COLUMN
import earth.worldwind.formats.gpkg.GeoPackage.Companion.TILE_Z_COLUMN
import earth.worldwind.render.image.ImageSource
import mil.nga.geopackage.BoundingBox
import mil.nga.geopackage.GeoPackage
import mil.nga.geopackage.GeoPackageCore
import mil.nga.geopackage.GeoPackageManager
import mil.nga.geopackage.extension.coverage.GriddedCoverageDataType
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

actual fun openOrCreateGeoPackage(pathName: String, isReadOnly: Boolean): GeoPackageCore {
    var file = File(pathName)
    if (!isReadOnly && !file.exists()) file = GeoPackageManager.create(file, false)
    return GeoPackageManager.open(!isReadOnly, file)
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
): List<Pair<Geometry, String?>> = (geoPackage as GeoPackage).getFeatureDao(tableName).queryForAll().mapNotNull { row ->
    val geom = row.getGeometry()?.geometry?.takeIf { !it.isEmpty } ?: return@mapNotNull null
    geom to (row.getValue(FEATURE_PROPERTIES_COLUMN) as? String)
}

actual fun insertCachedFeatures(
    geoPackage: GeoPackageCore, tableName: String, rows: List<Pair<Geometry, String?>>,
) {
    if (rows.isEmpty()) return
    val featureDao = (geoPackage as GeoPackage).getFeatureDao(tableName)
    val now = System.currentTimeMillis()
    // `last_modified` may be absent if migration was skipped (pre-eviction GPKG, ensure
    // failed, etc.). Probe once and skip setValue rather than throw per row.
    val hasLastModified = runCatching { featureDao.table.getColumnIndex(LAST_MODIFIED_COLUMN) }.isSuccess
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
            if (hasLastModified) row.setValue(LAST_MODIFIED_COLUMN, now)
            featureDao.insert(row)
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

actual fun readFeatureTileRows(
    geoPackage: GeoPackageCore, tableName: String, z: Int, x: Int, y: Int,
): List<GpkgFeatureRow> {
    val featureDao = (geoPackage as GeoPackage).getFeatureDao(tableName)
    return featureDao.queryForFieldValues(
        mapOf(
            TILE_Z_COLUMN to z,
            TILE_X_COLUMN to x,
            TILE_Y_COLUMN to y,
        )
    ).map { row ->
        val geometry = row.getGeometry()?.geometry?.takeIf { !it.isEmpty }
        val properties = row.getValue(FEATURE_PROPERTIES_COLUMN) as? String
        GpkgFeatureRow(geometry, properties)
    }
}

actual fun replaceFeatureTileRows(
    geoPackage: GeoPackageCore, tableName: String, z: Int, x: Int, y: Int,
    rows: List<GpkgFeatureRow>,
) {
    val gpkg = geoPackage as GeoPackage
    val featureDao = gpkg.getFeatureDao(tableName)
    // Coords are integers — inlining is injection-safe; the JVM connection has no parameterised execSQL.
    val escapedTable = tableName.replace("\"", "\"\"")
    gpkg.database.execSQL(
        "DELETE FROM \"$escapedTable\" WHERE " +
                "$TILE_Z_COLUMN = $z AND " +
                "$TILE_X_COLUMN = $x AND " +
                "$TILE_Y_COLUMN = $y"
    )
    if (rows.isEmpty()) return
    val srsId = featureDao.geometryColumns.srsId
    val now = System.currentTimeMillis()
    val hasLastModified = runCatching { featureDao.table.getColumnIndex(LAST_MODIFIED_COLUMN) }.isSuccess
    featureDao.beginTransaction()
    var committed = false
    try {
        var inserted = 0
        for ((geometry, propertiesJson) in rows) {
            val row = featureDao.newRow()
            if (geometry != null) {
                row.geometry = GeoPackageGeometryData.create(srsId, geometry)
            }
            row.setValue(TILE_Z_COLUMN, z)
            row.setValue(TILE_X_COLUMN, x)
            row.setValue(TILE_Y_COLUMN, y)
            propertiesJson?.let { row.setValue(FEATURE_PROPERTIES_COLUMN, it) }
            if (hasLastModified) row.setValue(LAST_MODIFIED_COLUMN, now)
            featureDao.insert(row)
            if (++inserted % FEATURE_INSERT_BATCH == 0) featureDao.endAndBeginTransaction()
        }
        committed = true
    } finally {
        featureDao.endTransaction(committed)
    }
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