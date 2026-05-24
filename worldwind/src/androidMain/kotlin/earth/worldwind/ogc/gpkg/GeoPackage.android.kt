package earth.worldwind.ogc.gpkg

import android.database.sqlite.SQLiteDatabase
import androidx.core.graphics.scale
import earth.worldwind.render.image.ImageSource
import mil.nga.geopackage.BoundingBox
import mil.nga.geopackage.GeoPackage
import mil.nga.geopackage.GeoPackageCore
import mil.nga.geopackage.GeoPackageFactory
import mil.nga.geopackage.db.GeoPackageConnection
import mil.nga.geopackage.db.GeoPackageDatabase
import mil.nga.geopackage.db.GeoPackageTableCreator
import mil.nga.geopackage.extension.coverage.GriddedCoverageDataType
import mil.nga.geopackage.extension.nga.style.FeatureStyleExtension
import mil.nga.geopackage.tiles.user.TileTableMetadata
import mil.nga.sf.Geometry
import java.io.File
import kotlin.math.roundToInt

actual typealias CoverageData<TImage> = mil.nga.geopackage.extension.coverage.CoverageData<TImage>
actual typealias StyleRow = mil.nga.geopackage.extension.nga.style.StyleRow
actual typealias IconRow = mil.nga.geopackage.extension.nga.style.IconRow
actual typealias FeatureStyle = mil.nga.geopackage.extension.nga.style.FeatureStyle

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

actual fun getFeatureList(geoPackage: GeoPackageCore, tableName: String): List<Pair<Geometry, FeatureStyle?>> {
    val featureStyleExtension = FeatureStyleExtension(geoPackage as GeoPackage)
    return geoPackage.getFeatureDao(tableName).queryForAll().use { cursor ->
        cursor.mapNotNull { row ->
            row.getGeometry()?.geometry?.takeIf { !it.isEmpty }?.let { geometry ->
                geometry to featureStyleExtension.getFeatureStyle(tableName, row.id, geometry.geometryType)
            }
        }
    }
}

actual fun readCachedFeaturesWithProperties(
    geoPackage: GeoPackageCore, tableName: String,
): List<Pair<Geometry, String?>> = (geoPackage as GeoPackage).getFeatureDao(tableName).queryForAll().use { cursor ->
    cursor.mapNotNull { row ->
        val geom = row.getGeometry()?.geometry?.takeIf { !it.isEmpty } ?: return@mapNotNull null
        geom to (row.getValue(earth.worldwind.ogc.gpkg.GeoPackage.FEATURE_PROPERTIES_COLUMN) as? String)
    }
}

actual fun insertCachedFeatures(
    geoPackage: GeoPackageCore, tableName: String, rows: List<Pair<Geometry, String?>>,
) {
    val featureDao = (geoPackage as GeoPackage).getFeatureDao(tableName)
    for ((geometry, propertiesJson) in rows) {
        val row = featureDao.newRow()
        row.geometry = mil.nga.geopackage.geom.GeoPackageGeometryData.create(featureDao.geometryColumns.srsId, geometry)
        propertiesJson?.let { row.setValue(earth.worldwind.ogc.gpkg.GeoPackage.FEATURE_PROPERTIES_COLUMN, it) }
        featureDao.insert(row)
    }
}

actual fun truncateFeatureTable(geoPackage: GeoPackageCore, tableName: String) {
    (geoPackage as GeoPackage).getFeatureDao(tableName).deleteAll()
}

actual fun readFeatureTileRows(
    geoPackage: GeoPackageCore, tableName: String, z: Int, x: Int, y: Int,
): List<GpkgFeatureRow> {
    val featureDao = (geoPackage as GeoPackage).getFeatureDao(tableName)
    return featureDao.queryForFieldValues(
        mapOf(
            earth.worldwind.ogc.gpkg.GeoPackage.TILE_Z_COLUMN to z,
            earth.worldwind.ogc.gpkg.GeoPackage.TILE_X_COLUMN to x,
            earth.worldwind.ogc.gpkg.GeoPackage.TILE_Y_COLUMN to y,
        )
    ).use { cursor ->
        cursor.map { row ->
            val geometry = row.getGeometry()?.geometry?.takeIf { !it.isEmpty }
            val properties = row.getValue(earth.worldwind.ogc.gpkg.GeoPackage.FEATURE_PROPERTIES_COLUMN) as? String
            GpkgFeatureRow(geometry, properties)
        }
    }
}

actual fun replaceFeatureTileRows(
    geoPackage: GeoPackageCore, tableName: String, z: Int, x: Int, y: Int,
    rows: List<GpkgFeatureRow>,
) {
    val gpkg = geoPackage as GeoPackage
    val featureDao = gpkg.getFeatureDao(tableName)
    val escapedTable = tableName.replace("\"", "\"\"")
    gpkg.database.execSQL(
        "DELETE FROM \"$escapedTable\" WHERE " +
                "${earth.worldwind.ogc.gpkg.GeoPackage.TILE_Z_COLUMN} = $z AND " +
                "${earth.worldwind.ogc.gpkg.GeoPackage.TILE_X_COLUMN} = $x AND " +
                "${earth.worldwind.ogc.gpkg.GeoPackage.TILE_Y_COLUMN} = $y"
    )
    if (rows.isEmpty()) return
    val srsId = featureDao.geometryColumns.srsId
    for ((geometry, propertiesJson) in rows) {
        val row = featureDao.newRow()
        if (geometry != null) {
            row.geometry = mil.nga.geopackage.geom.GeoPackageGeometryData.create(srsId, geometry)
        }
        row.setValue(earth.worldwind.ogc.gpkg.GeoPackage.TILE_Z_COLUMN, z)
        row.setValue(earth.worldwind.ogc.gpkg.GeoPackage.TILE_X_COLUMN, x)
        row.setValue(earth.worldwind.ogc.gpkg.GeoPackage.TILE_Y_COLUMN, y)
        propertiesJson?.let { row.setValue(earth.worldwind.ogc.gpkg.GeoPackage.FEATURE_PROPERTIES_COLUMN, it) }
        featureDao.insert(row)
    }
}

actual fun buildImageSource(iconRow: IconRow) = ImageSource.fromBitmap(iconRow.dataBitmap.let { bitmap ->
    val width = iconRow.width?.roundToInt() ?: bitmap.width
    val height = iconRow.height?.roundToInt() ?: bitmap.height
    bitmap.scale(width, height)
})