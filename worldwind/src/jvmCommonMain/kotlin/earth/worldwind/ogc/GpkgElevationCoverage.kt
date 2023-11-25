package earth.worldwind.ogc

import earth.worldwind.WorldWind
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.launch

/**
 * Generates elevations from OGC Geo Package database.
 */
open class GpkgElevationCoverage(pathName: String, tableName: String) : TiledElevationCoverage() {
    init {
        mainScope.launch {
            try {
                val geoPackage = GeoPackage(pathName)
                val content = geoPackage.content.firstOrNull { content -> content.tableName == tableName }
                requireNotNull(content) { "Missing coverage content for '$tableName'" }
                val metadata = geoPackage.griddedCoverages.firstOrNull { gc -> gc.tileMatrixSetName == tableName }
                requireNotNull(metadata) { "Missing gridded coverage metadata for '$tableName'" }
                tileMatrixSet = geoPackage.buildTileMatrixSet(content)
                elevationSourceFactory = GpkgElevationSourceFactory(content, metadata.datatype == "float")
                WorldWind.requestRedraw()
            } catch (logged: Throwable) {
                logMessage(
                    ERROR, "GpkgElevationCoverage", "constructor",
                    "Exception initializing GeoPackage coverage file:$pathName coverage:$tableName", logged
                )
            }
        }
    }
}