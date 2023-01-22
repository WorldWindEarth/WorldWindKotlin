package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.geom.TileMatrix
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationTileFactory

/**
 * Factory for constructing WCS version 1.0.0 URLs associated with WCS Get Coverage requests.
 */
open class Wcs100TileFactory(
    /**
     * The WCS service address use to build Get Coverage URLs.
     */
    protected val serviceAddress: String,
    /**
     * The coverage name of the desired WCS coverage.
     */
    protected val coverage: String,
    /**
     * Required WCS source output format
     */
    protected val outputFormat: String
): ElevationTileFactory {
    override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource {
        val urlString = urlForTile(tileMatrix, row, column)
        return ElevationSource.fromUrlString(urlString)
    }

    protected open fun urlForTile(tileMatrix: TileMatrix, row: Int, col: Int) = Uri.parse(serviceAddress).buildUpon()
        .appendQueryParameter("VERSION", "1.0.0")
        .appendQueryParameter("SERVICE", "WCS")
        .appendQueryParameter("REQUEST", "GetCoverage")
        .appendQueryParameter("COVERAGE", coverage)
        .appendQueryParameter("CRS", "EPSG:4326")
        .appendQueryParameter("BBOX", tileMatrix.tileSector(row, col).run {
            "${minLongitude.inDegrees},${minLatitude.inDegrees},${maxLongitude.inDegrees},${maxLatitude.inDegrees}"
        })
        .appendQueryParameter("WIDTH", tileMatrix.tileWidth.toString())
        .appendQueryParameter("HEIGHT", tileMatrix.tileHeight.toString())
        .appendQueryParameter("FORMAT", outputFormat)
        .build().toString()
}