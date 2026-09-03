package earth.worldwind.formats.geotiff

import earth.worldwind.geom.Sector
import earth.worldwind.util.Logger.INFO
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A tiled GeoTIFF opened for pyramid access: the resolution ladder (full-resolution image
 * plus every overview IFD), the georeference, and the sampling used to resample an
 * arbitrary geographic sector into one engine tile.
 *
 * The file is never read whole. Opening parses tags only; each rendered tile picks the
 * overview closest to its own resolution and decodes just the blocks it lands on, so a
 * multi-gigabyte orthophoto costs the same per tile as a small one.
 *
 * A file **without** overviews still works, but every zoomed-out tile has to decimate the
 * full-resolution raster — the open call logs a warning suggesting `gdaladdo` /
 * COG conversion, which is the fix.
 *
 * Instances are safe to sample from multiple coroutines: sampling holds no shared mutable
 * state (block caching is per call) and [TiffDataSource] implementations serialize reads.
 */
class GeoTiffDataset private constructor(
    private val source: TiffDataSource,
    private val isLittleEndian: Boolean,
    /** Resolution ladder, finest first: index 0 is the full-resolution raster. */
    val levels: List<TiffDirectory>,
    val geoReference: GeoTiffGeoReference,
) {
    /** Full-resolution raster. */
    val primary get() = levels[0]
    /** Geographic bounds of the raster. */
    val sector: Sector = geoReference.sector(primary.imageWidth, primary.imageHeight)
    /** Native resolution in degrees of latitude per pixel — the finest detail the file holds. */
    val degreesPerPixel = nativeDegreesPerPixel()
    /**
     * True when the raster looks like a height field rather than a picture: one band of
     * 16-bit-or-wider samples with no palette. Callers that know better (a single-band
     * 8-bit hillshade, say) can ignore it and use the sampler they want.
     */
    val isElevation get() = primary.samplesPerPixel == 1 &&
        primary.bitsPerFirstSample >= 16 &&
        primary.photometricInterpretation != TiffConstants.PhotometricInterpretation.RGB_PALETTE

    /** Release the underlying source. */
    fun close() = source.close()

    /**
     * Index of the ladder entry to sample for a tile of [degreesPerTexel] resolution — the
     * coarsest overview that still resolves the tile, so zoomed-out tiles read a small
     * overview instead of decimating the full-resolution raster.
     */
    fun selectLevel(degreesPerTexel: Double): Int {
        for (index in levels.indices.reversed()) {
            if (levelDegreesPerPixel(index) <= degreesPerTexel) return index
        }
        return 0
    }

    /** Resolution of ladder entry [index] in degrees of latitude per pixel. */
    fun levelDegreesPerPixel(index: Int) =
        degreesPerPixel * primary.imageHeight / levels[index].imageHeight.coerceAtLeast(1)

    /**
     * Resample [sector] into a `width` × `height` grid of 16-bit elevations, north-up and
     * row-major with the engine's texel-centre convention. Missing posts — outside the
     * raster, `GDAL_NODATA`, or an undecodable block — come back as [Short.MIN_VALUE],
     * which is the engine's own missing-data sentinel.
     *
     * Returns `null` when the sector doesn't overlap the raster at all.
     */
    fun sampleElevation(sector: Sector, width: Int, height: Int): ShortArray? {
        val mapper = TileMapper.create(this, sector, width, height) ?: return null
        val sampler = LevelSampler(mapper.level)
        val out = ShortArray(width * height) { NO_DATA }
        val raster = DoubleArray(2)
        for (y in mapper.firstRow..mapper.lastRow) {
            for (x in mapper.firstColumn..mapper.lastColumn) {
                if (!mapper.rasterAt(x, y, raster)) continue
                val value = sampler.bilinear(raster[0], raster[1], 0)
                if (value.isNaN()) continue
                out[y * width + x] = value.roundToInt().coerceIn(MIN_ELEVATION, MAX_ELEVATION).toShort()
            }
        }
        return out
    }

    /**
     * Resample [sector] into a `width` × `height` grid of `0xAARRGGBB` pixels. Pixels with
     * no source coverage (outside the raster, nodata, or an undecodable block) are fully
     * transparent, so a tile that only partly overlaps the image composites correctly over
     * whatever is beneath it.
     *
     * Returns `null` when the sector doesn't overlap the raster, or when the pixel layout
     * isn't one this decoder renders.
     */
    fun sampleArgb(sector: Sector, width: Int, height: Int): IntArray? {
        val mapper = TileMapper.create(this, sector, width, height) ?: return null
        val dir = levels[mapper.level]
        val converter = ArgbConverter.of(dir) ?: return null
        val sampler = LevelSampler(mapper.level)
        val out = IntArray(width * height)
        val raster = DoubleArray(2)
        val samples = FloatArray(dir.samplesPerPixel)
        for (y in mapper.firstRow..mapper.lastRow) {
            for (x in mapper.firstColumn..mapper.lastColumn) {
                if (!mapper.rasterAt(x, y, raster)) continue
                if (!sampler.nearest(raster[0], raster[1], samples)) continue
                out[y * width + x] = converter.toArgb(samples)
            }
        }
        return out
    }

    /** Native resolution in degrees of latitude per pixel, from the model transform. */
    private fun nativeDegreesPerPixel(): Double {
        val size = geoReference.pixelSizeY
        val degrees = if (geoReference.crs is GeoTiffCrs.Geographic) size else size / METRES_PER_DEGREE
        return if (degrees > 0.0) degrees else sector.deltaLatitude.inDegrees / primary.imageHeight
    }

    /**
     * Maps engine tile texels onto full-resolution raster coordinates.
     *
     * The geodetic → raster transform is evaluated on a coarse grid and interpolated
     * between nodes rather than run per texel: bilinear interpolation reproduces an affine
     * (geographic) transform exactly, and for a projected raster the residual curvature
     * over one grid cell is far below a pixel. The grid spans only the part of the tile
     * the raster actually covers, so a zoomed-out tile that holds the whole dataset in a
     * few texels still interpolates over the dataset's own extent, not over 90° of sky.
     */
    private class TileMapper(
        private val nodes: DoubleArray, private val gridSize: Int,
        private val originX: Double, private val originY: Double,
        private val stepX: Double, private val stepY: Double,
        val firstColumn: Int, val lastColumn: Int, val firstRow: Int, val lastRow: Int,
        val level: Int, private val levelScaleX: Double, private val levelScaleY: Double,
    ) {
        /** Raster coordinate of texel ([x], [y])'s centre, in the selected level's pixel space. */
        fun rasterAt(x: Int, y: Int, result: DoubleArray): Boolean {
            val u = (x + 0.5 - originX) / stepX
            val v = (y + 0.5 - originY) / stepY
            val cellX = floor(u).toInt().coerceIn(0, gridSize - 1)
            val cellY = floor(v).toInt().coerceIn(0, gridSize - 1)
            val fx = u - cellX
            val fy = v - cellY
            val stride = (gridSize + 1) * 2
            val at00 = cellY * stride + cellX * 2
            val at10 = at00 + 2
            val at01 = at00 + stride
            val at11 = at01 + 2
            val x00 = nodes[at00]; val x10 = nodes[at10]; val x01 = nodes[at01]; val x11 = nodes[at11]
            if (x00.isNaN() || x10.isNaN() || x01.isNaN() || x11.isNaN()) return false
            val top = x00 + (x10 - x00) * fx
            val bottom = x01 + (x11 - x01) * fx
            result[0] = (top + (bottom - top) * fy) * levelScaleX
            val y00 = nodes[at00 + 1]; val y10 = nodes[at10 + 1]
            val y01 = nodes[at01 + 1]; val y11 = nodes[at11 + 1]
            val topY = y00 + (y10 - y00) * fx
            val bottomY = y01 + (y11 - y01) * fx
            result[1] = (topY + (bottomY - topY) * fy) * levelScaleY
            return true
        }

        companion object {
            /** Interpolation cells per axis. */
            private const val GRID_SIZE = 8

            fun create(dataset: GeoTiffDataset, sector: Sector, width: Int, height: Int): TileMapper? {
                if (width <= 0 || height <= 0) return null
                val overlap = Sector(sector)
                if (!overlap.intersect(dataset.sector)) return null
                val minLon = sector.minLongitude.inDegrees
                val maxLat = sector.maxLatitude.inDegrees
                val degreesPerColumn = sector.deltaLongitude.inDegrees / width
                val degreesPerRow = sector.deltaLatitude.inDegrees / height
                if (degreesPerColumn <= 0.0 || degreesPerRow <= 0.0) return null
                // Texel span of the overlap, widened by one texel so bilinear taps at the
                // border still see source data.
                val firstColumn = (((overlap.minLongitude.inDegrees - minLon) / degreesPerColumn) - 1.0)
                    .toInt().coerceIn(0, width - 1)
                val lastColumn = (((overlap.maxLongitude.inDegrees - minLon) / degreesPerColumn) + 1.0)
                    .toInt().coerceIn(firstColumn, width - 1)
                val firstRow = (((maxLat - overlap.maxLatitude.inDegrees) / degreesPerRow) - 1.0)
                    .toInt().coerceIn(0, height - 1)
                val lastRow = (((maxLat - overlap.minLatitude.inDegrees) / degreesPerRow) + 1.0)
                    .toInt().coerceIn(firstRow, height - 1)

                val level = dataset.selectLevel(sector.deltaLatitude.inDegrees / height)
                val levelDir = dataset.levels[level]
                val levelScaleX = levelDir.imageWidth.toDouble() / dataset.primary.imageWidth
                val levelScaleY = levelDir.imageHeight.toDouble() / dataset.primary.imageHeight

                // Grid nodes span the covered texel rectangle, in continuous texel coordinates.
                val originX = firstColumn.toDouble()
                val originY = firstRow.toDouble()
                val spanX = (lastColumn + 1).toDouble() - originX
                val spanY = (lastRow + 1).toDouble() - originY
                val stepX = spanX / GRID_SIZE
                val stepY = spanY / GRID_SIZE
                val nodes = DoubleArray((GRID_SIZE + 1) * (GRID_SIZE + 1) * 2)
                val raster = DoubleArray(2)
                var at = 0
                for (gy in 0..GRID_SIZE) {
                    val latitude = maxLat - (originY + gy * stepY) * degreesPerRow
                    for (gx in 0..GRID_SIZE) {
                        val longitude = minLon + (originX + gx * stepX) * degreesPerColumn
                        if (dataset.geoReference.geodeticToRaster(latitude, longitude, raster)) {
                            nodes[at] = raster[0]
                            nodes[at + 1] = raster[1]
                        } else {
                            nodes[at] = Double.NaN
                            nodes[at + 1] = Double.NaN
                        }
                        at += 2
                    }
                }
                return TileMapper(
                    nodes, GRID_SIZE, originX, originY, stepX, stepY,
                    firstColumn, lastColumn, firstRow, lastRow, level, levelScaleX, levelScaleY
                )
            }
        }
    }

    /**
     * Reads samples from one ladder entry, decoding blocks on demand and keeping the last
     * few around. Sampling walks the output row by row, so a handful of blocks covers the
     * source band a row lands on; the cache is per-sampler (per tile), which keeps the
     * class free of cross-thread state.
     */
    private inner class LevelSampler(level: Int) {
        private val dir = levels[level]
        private val cached = HashMap<Int, FloatArray?>()
        private val order = ArrayDeque<Int>()
        // Nodata as primitives: the map lookup below runs once per sample, and reading a
        // nullable Double there would box on every one of a tile's ~200k samples.
        private val hasNoData = dir.noData != null
        private val noDataValue = dir.noData ?: 0.0
        // Sampling walks the output row by row, so consecutive samples almost always land in
        // the block the previous one did. Remembering the last hit keeps the common case off
        // the boxed-key HashMap entirely.
        private var lastIndex = -1
        private var lastBlock: FloatArray? = null

        private fun block(index: Int): FloatArray? {
            if (index == lastIndex) return lastBlock
            val hit = cached[index]
            if (hit != null || cached.containsKey(index)) { // null value = known-missing block
                lastIndex = index
                lastBlock = hit
                return hit
            }
            val decoded = TiffBlockCodec.decodeBlock(source, dir, isLittleEndian, index)
            cached[index] = decoded
            order.addLast(index)
            if (order.size > MAX_CACHED_BLOCKS) {
                val evicted = order.removeFirst()
                cached.remove(evicted)
                if (evicted == lastIndex) {
                    lastIndex = -1
                    lastBlock = null
                }
            }
            lastIndex = index
            lastBlock = decoded
            return decoded
        }

        /** Raw sample of [band] at integer pixel ([x], [y]), or NaN when unavailable / nodata. */
        private fun sampleAt(x: Int, y: Int, band: Int): Float {
            if (x < 0 || y < 0 || x >= dir.imageWidth || y >= dir.imageHeight) return Float.NaN
            val blockX = x / dir.blockWidth
            val blockY = y / dir.blockHeight
            val data = block(dir.blockIndex(blockX, blockY)) ?: return Float.NaN
            val inX = x - blockX * dir.blockWidth
            val inY = y - blockY * dir.blockHeight
            val at = (inY * dir.blockWidth + inX) * dir.samplesPerPixel + band
            if (at < 0 || at >= data.size) return Float.NaN
            val value = data[at]
            return if (isMissing(value)) Float.NaN else value
        }

        /** All bands at the pixel nearest ([px], [py]); false when the pixel has no data. */
        fun nearest(px: Double, py: Double, result: FloatArray): Boolean {
            val x = floor(px).toInt()
            val y = floor(py).toInt()
            for (band in result.indices) {
                val value = sampleAt(x, y, band)
                if (value.isNaN()) return false
                result[band] = value
            }
            return true
        }

        /**
         * Bilinear sample of [band] at continuous raster position ([px], [py]) — pixel
         * centres sit on half-integers, so the taps straddle the sample point. Missing
         * neighbours drop out of the weighted average instead of poisoning it; a point with
         * no valid neighbour returns NaN.
         */
        fun bilinear(px: Double, py: Double, band: Int): Float {
            val fx = px - 0.5
            val fy = py - 0.5
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val wx = fx - x0
            val wy = fy - y0
            var sum = 0.0
            var weight = 0.0
            for (dy in 0..1) {
                for (dx in 0..1) {
                    val value = sampleAt(x0 + dx, y0 + dy, band)
                    if (value.isNaN()) continue
                    val w = (if (dx == 0) 1.0 - wx else wx) * (if (dy == 0) 1.0 - wy else wy)
                    if (w <= 0.0) continue
                    sum += value * w
                    weight += w
                }
            }
            if (weight > 0.0) return (sum / weight).toFloat()
            // Every tap was missing except possibly a zero-weight one — fall back to nearest.
            return sampleAt(floor(px).toInt(), floor(py).toInt(), band)
        }

        private fun isMissing(value: Float): Boolean {
            if (value.isNaN()) return true
            // Float32 DEMs routinely mark voids with ±3.4e38 whether or not GDAL_NODATA says so.
            if (value <= -SENTINEL_MAGNITUDE || value >= SENTINEL_MAGNITUDE) return true
            if (!hasNoData) return false
            return abs(value - noDataValue) <= abs(noDataValue) * NO_DATA_TOLERANCE + Float.MIN_VALUE
        }
    }

    companion object {
        private const val NO_DATA = Short.MIN_VALUE
        /** Engine elevations are Int16 metres; clamp rather than wrap on absurd samples. */
        private const val MIN_ELEVATION = Short.MIN_VALUE + 1
        private const val MAX_ELEVATION = Short.MAX_VALUE.toInt()
        private const val METRES_PER_DEGREE = 111320.0
        /** Blocks kept while resampling one tile — enough for the source band a texel row lands on. */
        private const val MAX_CACHED_BLOCKS = 24
        private const val NO_DATA_TOLERANCE = 1e-6
        private const val SENTINEL_MAGNITUDE = 1e30f

        /**
         * Open [source] as a pyramid-addressable GeoTIFF. Returns `null` when the file has no
         * usable raster or no georeference the engine can invert — both of which are logged.
         * The caller owns [source]; [close] releases it.
         */
        fun open(source: TiffDataSource): GeoTiffDataset? {
            val layout = TiffStructure.read(source)
            // Masks and thumbnails are IFDs too — keep only the imagery, then order it
            // finest-first so index 0 is always the full-resolution raster. Equal-width IFDs
            // are the pages of a multi-page TIFF rather than a pyramid step: keep the first
            // of each size so level selection maps resolution to a single raster.
            val rasters = layout.directories.filterNot { it.isMask }
                .sortedByDescending { it.imageWidth }
                .distinctBy { it.imageWidth }
            if (rasters.isEmpty()) {
                log(WARN, "GeoTIFF contains no readable raster")
                return null
            }
            // Geo tags live on the full-resolution IFD; overviews inherit its transform, scaled.
            val geoReference = GeoTiffGeoReference.from(rasters[0]) ?: return null
            if (rasters.size == 1 && rasters[0].imageWidth > OVERVIEW_ADVICE_WIDTH) {
                log(
                    INFO, "GeoTIFF ${rasters[0].imageWidth}x${rasters[0].imageHeight} has no overviews — " +
                        "zoomed-out tiles must decimate the full-resolution raster. Add overviews " +
                        "(gdaladdo, or convert to a COG) for faster display."
                )
            }
            return GeoTiffDataset(source, layout.isLittleEndian, rasters, geoReference)
        }

        /** Rasters wider than this are worth an overview-ladder nudge in the log. */
        private const val OVERVIEW_ADVICE_WIDTH = 4096
    }
}
