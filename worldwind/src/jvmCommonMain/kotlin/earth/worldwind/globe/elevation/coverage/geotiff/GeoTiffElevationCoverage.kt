package earth.worldwind.globe.elevation.coverage.geotiff

import earth.worldwind.formats.geotiff.FileTiffDataSource
import earth.worldwind.formats.geotiff.GeoTiffDataset
import earth.worldwind.formats.geotiff.TiffDataSource
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log
import java.io.File
import kotlin.math.roundToInt

/**
 * Terrain served from a tiled GeoTIFF digital elevation model — a single-band Int16 or
 * Float32 raster, typically a national DEM tile, a LiDAR-derived DTM, or a drone survey's
 * elevation output.
 *
 * The pyramid is built over the file's own extent (not the whole globe) so the coverage's
 * sampler never wraps longitude across an unrelated part of the world, and each tile is
 * resampled from the overview level closest to its resolution. `GDAL_NODATA` voids and
 * areas the file doesn't cover come through as the engine's missing-post sentinel, so a
 * partial DEM composites over coarser coverages instead of punching holes in the terrain.
 *
 * The coverage holds the file open while it is attached to the globe; [close] releases it.
 */
open class GeoTiffElevationCoverage protected constructor(
    /** The open GeoTIFF backing this coverage. */
    val dataset: GeoTiffDataset,
) : TiledElevationCoverage(buildTileMatrixSet(dataset), GeoTiffElevationSourceFactory(dataset)) {

    init {
        sector.copy(dataset.sector)
        // ~128 KB per tile; hold a working set big enough for a full screen of terrain.
        setupCoverageCache(TILE_POSTS.toLong() * TILE_POSTS * 2L * 512L)
    }

    /** Release the underlying file. The coverage returns no elevations afterwards. */
    fun close() = dataset.close()

    override fun clone() = GeoTiffElevationCoverage(dataset).also {
        it.displayName = displayName
        it.sector.copy(sector)
    }

    companion object {
        /** Upper bound on posts per pyramid tile. Detail comes from subdividing, not from
         *  bigger tiles; a raster smaller than this gets tiles sized to its own resolution. */
        const val TILE_POSTS = 256

        /**
         * Open [file] as an elevation coverage. Returns `null` when the file isn't a GeoTIFF
         * the engine can read or georeference — the reason is logged. On success the coverage
         * owns the file handle and releases it in [close].
         */
        fun create(file: File, displayName: String = file.name): GeoTiffElevationCoverage? {
            val source = FileTiffDataSource(file)
            return create(source, displayName) ?: run { source.close(); null }
        }

        /** Open [source] as an elevation coverage; `null` when it can't be read or georeferenced. */
        fun create(source: TiffDataSource, displayName: String): GeoTiffElevationCoverage? {
            val dataset = GeoTiffDataset.open(source) ?: return null
            return create(dataset, displayName)
        }

        /** Build a coverage over an already-open [dataset]. */
        fun create(dataset: GeoTiffDataset, displayName: String): GeoTiffElevationCoverage {
            if (!dataset.isElevation) log(
                WARN, "GeoTIFF '$displayName' has ${dataset.primary.samplesPerPixel} band(s) of " +
                    "${dataset.primary.bitsPerFirstSample}-bit samples — reading band 1 as elevation anyway"
            )
            return GeoTiffElevationCoverage(dataset).also { it.displayName = displayName }
        }

        /**
         * A tile pyramid over the raster's own extent. Level 0 is split so its tiles are
         * roughly square in degrees, and the ladder descends to the file's native resolution.
         */
        private fun buildTileMatrixSet(dataset: GeoTiffDataset): TileMatrixSet {
            val sector = dataset.sector
            val aspect = sector.deltaLongitude.inDegrees / sector.deltaLatitude.inDegrees
            val matrixWidth = if (aspect >= 1.0) aspect.roundToInt().coerceAtLeast(1) else 1
            val matrixHeight = if (aspect >= 1.0) 1 else (1.0 / aspect).roundToInt().coerceAtLeast(1)
            // Cap level-0 tiles at the raster's own post spacing so a small DEM isn't
            // upsampled into 256-post tiles it has no detail to fill.
            val posts = minOf(
                TILE_POSTS,
                dataset.primary.imageWidth / matrixWidth,
                dataset.primary.imageHeight / matrixHeight,
            ).coerceAtLeast(MIN_TILE_POSTS)
            return TileMatrixSet.fromTilePyramid(
                sector, matrixWidth, matrixHeight, posts, posts, dataset.degreesPerPixel.degrees
            )
        }

        /** Floor on tile posts — below this the pyramid costs more in tiles than it saves. */
        private const val MIN_TILE_POSTS = 32
    }
}
