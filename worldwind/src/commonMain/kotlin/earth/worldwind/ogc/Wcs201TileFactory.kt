package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.geom.TileMatrix
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationTileFactory

open class Wcs201TileFactory(
    /**
     * The WCS service address use to build Get Coverage URLs.
     */
    var serviceAddress: String,
    /**
     * The coverage id of the desired WCS coverage.
     */
    var coverageId: String,
    /**
     * Required WCS source image format
     */
    var imageFormat: String
): ElevationTileFactory {
    override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource {
        val urlString = urlForTile(tileMatrix, row, column)
        return ElevationSource.fromUrlString(urlString)
    }

    protected open fun urlForTile(tileMatrix: TileMatrix, row: Int, col: Int): String {
        val sector = tileMatrix.tileSector(row, col)
        return Uri.parse(serviceAddress).buildUpon()
            .appendQueryParameter("VERSION", "2.0.1")
            .appendQueryParameter("SERVICE", "WCS")
            .appendQueryParameter("REQUEST", "GetCoverage")
            .appendQueryParameter("COVERAGEID", coverageId)
            .appendQueryParameter("FORMAT", imageFormat)
            .appendQueryParameter("SUBSET", sector.run { "Lat(${minLatitude.inDegrees},${maxLatitude.inDegrees})" })
            .appendQueryParameter("SUBSET", sector.run { "Long(${minLongitude.inDegrees},${maxLongitude.inDegrees})" })
            .appendQueryParameter("SCALESIZE", tileMatrix.run {
                "http://www.opengis.net/def/axis/OGC/1/i($tileWidth),http://www.opengis.net/def/axis/OGC/1/j($tileHeight)"
            })
            .appendQueryParameter("OVERVIEWPOLICY", "NEAREST")
            .build().toString()
    }
}