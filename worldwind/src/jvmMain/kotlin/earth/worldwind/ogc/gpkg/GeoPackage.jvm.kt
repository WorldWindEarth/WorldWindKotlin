package earth.worldwind.ogc.gpkg

import earth.worldwind.render.image.ImageSource
import mil.nga.geopackage.GeoPackage
import mil.nga.geopackage.GeoPackageCore
import mil.nga.geopackage.GeoPackageManager
import mil.nga.geopackage.extension.nga.style.FeatureStyleExtension
import mil.nga.sf.Geometry
import java.io.File

actual typealias StyleRow = mil.nga.geopackage.extension.nga.style.StyleRow
actual typealias IconRow = mil.nga.geopackage.extension.nga.style.IconRow
actual typealias FeatureStyle = mil.nga.geopackage.extension.nga.style.FeatureStyle

actual fun openOrCreateGeoPackage(pathName: String, isReadOnly: Boolean): GeoPackageCore {
    var file = File(pathName)
    if (!isReadOnly && !file.exists()) file = GeoPackageManager.create(file, false)
    return GeoPackageManager.open(!isReadOnly, file)
}

actual fun getFeatureList(geoPackage: GeoPackageCore, tableName: String): List<Pair<Geometry, FeatureStyle?>> {
    val featureStyleExtension = FeatureStyleExtension(geoPackage as GeoPackage)
    return geoPackage.getFeatureDao(tableName).queryForAll().mapNotNull { row ->
        row.getGeometry()?.geometry?.takeIf { !it.isEmpty }?.let { geometry ->
            geometry to featureStyleExtension.getFeatureStyle(tableName, row.id, geometry.geometryType)
        }
    }
}

actual fun buildImageSource(iconRow: IconRow) = ImageSource.fromImage(iconRow.dataImage)