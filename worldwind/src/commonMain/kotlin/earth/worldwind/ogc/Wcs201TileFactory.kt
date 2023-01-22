package earth.worldwind.ogc

import com.eygraber.uri.Uri
import earth.worldwind.geom.TileMatrix
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationTileFactory

open class Wcs201TileFactory(
    /**
     * The WCS service address use to build Get Coverage URLs.
     */
    protected val serviceAddress: String,
    /**
     * The coverage id of the desired WCS coverage.
     */
    protected val coverageId: String,
    /**
     * Required WCS source output format
     */
    protected val outputFormat: String,
    /**
     * Axis labels from coverage description
     */
    protected val axisLabels: List<String> = listOf("Lat", "Long")
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
            .appendQueryParameter("FORMAT", outputFormat)
            .appendQueryParameter("SUBSET", sector.run { "${axisLabels[0]}(${minLatitude.inDegrees},${maxLatitude.inDegrees})" })
            .appendQueryParameter("SUBSET", sector.run { "${axisLabels[1]}(${minLongitude.inDegrees},${maxLongitude.inDegrees})" })
            .appendQueryParameter("SCALESIZE", tileMatrix.run { "${axisLabels[1]}($tileWidth),${axisLabels[0]}($tileHeight)" })
            .appendQueryParameter("OVERVIEWPOLICY", "NEAREST")
            .build().toString()
    }
}