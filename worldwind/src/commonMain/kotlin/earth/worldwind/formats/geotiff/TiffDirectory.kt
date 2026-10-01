package earth.worldwind.formats.geotiff

import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log

/**
 * One parsed TIFF Image File Directory — a single raster in the file. A pyramided GeoTIFF
 * (a COG, or anything `gdaladdo` has touched) holds the full-resolution image in the first
 * IFD and each successively coarser overview in its own IFD; [GeoTiffDataset] keeps them
 * as a resolution ladder and picks one per rendered tile.
 *
 * Strip-organized rasters are normalized to the tiled shape: a strip is just a block as
 * wide as the image and [blockHeight] rows tall, so block indexing and decoding stay on
 * one path.
 */
class TiffDirectory internal constructor(
    val imageWidth: Int,
    val imageHeight: Int,
    /** Block width in pixels — `TileWidth`, or the image width for strips. */
    val blockWidth: Int,
    /** Block height in pixels — `TileLength`, or `RowsPerStrip` for strips. */
    val blockHeight: Int,
    /** True when the raster is tiled (`TileOffsets`), false when striped (`StripOffsets`). */
    val isTiled: Boolean,
    val blockOffsets: LongArray,
    val blockByteCounts: LongArray,
    val samplesPerPixel: Int,
    val bitsPerSample: IntArray,
    /** Per-sample `SampleFormat` (unsigned / signed / IEEE float), defaulted to unsigned. */
    val sampleFormat: IntArray,
    val compression: Int,
    val predictor: Int,
    val photometricInterpretation: Int,
    val planarConfiguration: Int,
    /** `ColorMap` palette as 16-bit R,G,B runs, or `null` when the raster isn't palettized. */
    val colorMap: IntArray?,
    /** `ExtraSamples` — a first entry of 1 (associated) or 2 (unassociated) marks an alpha band. */
    val extraSamples: IntArray,
    /** `GDAL_NODATA`, parsed from its ASCII payload; `null` when the tag is absent. */
    val noData: Double?,
    /** `NewSubfileType` bit field: bit 0 = reduced-resolution overview, bit 2 = transparency mask. */
    val subfileType: Int,
    val modelPixelScale: DoubleArray,
    val modelTiepoint: DoubleArray,
    val modelTransformation: DoubleArray,
    val geoKeyDirectory: IntArray,
    val geoDoubleParams: DoubleArray,
    val geoAsciiParams: String,
) {
    /** Number of blocks across the raster. */
    val blocksAcross = (imageWidth + blockWidth - 1) / blockWidth
    /** Number of blocks down the raster. */
    val blocksDown = (imageHeight + blockHeight - 1) / blockHeight
    /** Bits of the first sample — the elevation / gray band, and the width every band shares
     *  in the layouts we decode. */
    val bitsPerFirstSample get() = bitsPerSample.firstOrNull() ?: 8
    /** Bytes one pixel occupies in a decoded block (all samples, chunky layout). */
    val bytesPerPixel get() = bitsPerSample.sumOf { it / 8 }.coerceAtLeast(1)
    /** True when this IFD is a reduced-resolution overview of the full-size image. */
    val isOverview get() = subfileType and 1 != 0
    /** True when this IFD is a transparency / per-band mask rather than imagery. */
    val isMask get() = subfileType and 4 != 0 ||
        photometricInterpretation == TiffConstants.PhotometricInterpretation.TRANSPARENCY_MASK
    /** Index of the alpha band within a chunky pixel, or -1 when the raster has no alpha. */
    val alphaBand get() = if (extraSamples.isNotEmpty() && extraSamples[0] in 1..2) samplesPerPixel - 1 else -1

    /**
     * GeoKey directory flattened to `key -> value`. Keys stored in the double-parameter array
     * resolve to their referenced entry; ASCII keys are skipped, since none of the keys the
     * engine acts on are textual.
     *
     * Lazy because most rasters never need it, and shared across the threads that decode
     * tiles, so the default synchronized initialization is the right one.
     */
    val geoKeys: Map<Int, Double> by lazy { parseGeoKeys() }

    /**
     * True when the raster carries a vertical CRS — the one place GeoTIFF states outright that
     * its pixels are heights, since only a height field is georeferenced vertically. Absent
     * from most DEMs all the same, so it confirms elevation rather than ruling it out.
     */
    val hasVerticalCrs get() = geoKeys.containsKey(GeoTiffConstants.VERTICAL_CS_TYPE_GEO_KEY) ||
        geoKeys.containsKey(GeoTiffConstants.VERTICAL_DATUM_GEO_KEY) ||
        geoKeys.containsKey(GeoTiffConstants.VERTICAL_UNITS_GEO_KEY)

    private fun parseGeoKeys(): Map<Int, Double> {
        if (geoKeyDirectory.size < 4) return emptyMap()
        val count = geoKeyDirectory[3]
        val keys = HashMap<Int, Double>(count.coerceIn(0, 256))
        for (i in 0 until count) {
            val at = 4 + i * 4
            if (at + 3 >= geoKeyDirectory.size) break
            val keyId = geoKeyDirectory[at]
            val location = geoKeyDirectory[at + 1]
            val valueOffset = geoKeyDirectory[at + 3]
            when (location) {
                0 -> keys[keyId] = valueOffset.toDouble()
                GeoTiffConstants.GEO_DOUBLE_PARAMS -> geoDoubleParams.getOrNull(valueOffset)?.let { keys[keyId] = it }
            }
        }
        return keys
    }

    fun blockIndex(blockX: Int, blockY: Int) = blockY * blocksAcross + blockX

    /** Log [message] at most once for this raster. An unsupported layout or a damaged
     *  container is a property of the whole file, so the alternative is the same warning
     *  once per block of every tile the camera passes over. */
    internal fun warnOnce(message: String) {
        if (warned) return
        warned = true
        log(WARN, message)
    }

    private var warned = false

    /** Uncompressed byte size of a full block — what the decompressors size their output to. */
    val blockByteSize get() = blockWidth * blockHeight * bytesPerPixel
}
