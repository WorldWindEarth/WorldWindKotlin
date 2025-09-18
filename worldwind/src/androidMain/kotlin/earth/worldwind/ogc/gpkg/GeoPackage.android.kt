package earth.worldwind.ogc.gpkg

import android.database.sqlite.SQLiteDatabase
import earth.worldwind.render.image.ImageSource
import mil.nga.geopackage.GeoPackage
import mil.nga.geopackage.GeoPackageCore
import mil.nga.geopackage.GeoPackageFactory
import mil.nga.geopackage.db.GeoPackageConnection
import mil.nga.geopackage.db.GeoPackageDatabase
import mil.nga.geopackage.db.GeoPackageTableCreator
import mil.nga.geopackage.extension.nga.style.FeatureStyleExtension
import mil.nga.sf.Geometry
import java.io.File

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

actual fun buildImageSource(iconRow: IconRow) = ImageSource.fromBitmap(iconRow.dataBitmap)