package earth.worldwind.ogc.gpkg

import mil.nga.geopackage.GeoPackageCore
import mil.nga.geopackage.GeoPackageManager
import java.io.File

actual fun openOrCreateGeoPackage(pathName: String, isReadOnly: Boolean): GeoPackageCore {
    var file = File(pathName)
    if (!isReadOnly && !file.exists()) file = GeoPackageManager.create(file, false)
    return GeoPackageManager.open(!isReadOnly, file)
}