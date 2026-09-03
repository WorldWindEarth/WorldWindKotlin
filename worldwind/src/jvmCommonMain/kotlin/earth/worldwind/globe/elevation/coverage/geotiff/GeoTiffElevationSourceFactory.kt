package earth.worldwind.globe.elevation.coverage.geotiff

import earth.worldwind.formats.geotiff.GeoTiffDataset
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrix
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.util.Logger.DEBUG
import earth.worldwind.util.Logger.isLoggable
import earth.worldwind.util.Logger.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.Buffer
import java.nio.ShortBuffer

/** Builds one [ElevationSource] per pyramid tile, each reading its own window of the GeoTIFF. */
internal class GeoTiffElevationSourceFactory(private val dataset: GeoTiffDataset) : ElevationSourceFactory {
    override val contentType = "GeoTIFF"

    override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource =
        ElevationSource.fromElevationDataFactory(
            GeoTiffElevationDataFactory(
                dataset, tileMatrix.tileSector(row, column), tileMatrix.tileWidth, tileMatrix.tileHeight
            )
        )
}

/** Resamples one tile's posts out of the GeoTIFF, off the render thread. */
internal class GeoTiffElevationDataFactory(
    private val dataset: GeoTiffDataset,
    private val sector: Sector,
    private val tileWidth: Int,
    private val tileHeight: Int,
) : ElevationSource.ElevationDataFactory {
    override suspend fun fetchElevationData(): Buffer? = withContext(Dispatchers.IO) {
        val posts = dataset.sampleElevation(sector, tileWidth, tileHeight) ?: return@withContext null
        if (isLoggable(DEBUG)) log(DEBUG, "GeoTIFF elevation tile loaded: $sector ${tileWidth}x$tileHeight")
        ShortBuffer.wrap(posts)
    }

    override fun toString() = "GeoTIFF elevation tile $sector"
}
