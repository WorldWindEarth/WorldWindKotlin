package earth.worldwind.formats.geotiff

/**
 * What a GeoTIFF's pixels are meant to be — a picture to drape, or a height field to build
 * terrain from.
 *
 * GeoTIFF describes pixel layout, not meaning: no tag declares "this raster is terrain", so
 * the answer is inferred from band count, colour model, sample format and the vertical CRS.
 * That inference is decisive for almost every real file, and [AMBIGUOUS] marks the one
 * combination that genuinely is not decidable from the header.
 */
enum class GeoTiffContent {
    /** A picture: RGB(A), palette, or a grayscale / panchromatic image. */
    IMAGERY,

    /** A height field: one band of continuous values. */
    ELEVATION,

    /**
     * One unsigned integer band whose header fits both readings — terrain in metres and
     * 12-bit sensor counts both live inside `0..4095`. [GeoTiffDataset.probeContent] settles
     * the cases it can from the pixels; when it cannot, ask rather than guess.
     */
    AMBIGUOUS,
}

/** A [GeoTiffContent] verdict together with the evidence it rests on, for logs and import UI. */
class GeoTiffContentVerdict internal constructor(
    val content: GeoTiffContent,
    /** The single signal this verdict rests on, phrased for display (`"3 bands"`). */
    val reason: String,
) {
    override fun toString() = "$content ($reason)"
}

/**
 * Classify [dir] from its tags alone.
 *
 * Structural imagery evidence is tested first: a raster with several bands or a colour model
 * cannot be a height field whatever else it carries. The elevation signals that follow are
 * each conclusive on a single band — a vertical CRS, float samples, signed samples, or a
 * nodata value — leaving only unsigned integers undecided.
 */
internal fun classifyGeoTiff(dir: TiffDirectory): GeoTiffContentVerdict {
    if (dir.samplesPerPixel >= 3) return verdict(GeoTiffContent.IMAGERY, "${dir.samplesPerPixel} bands")
    when (dir.photometricInterpretation) {
        TiffConstants.PhotometricInterpretation.RGB ->
            return verdict(GeoTiffContent.IMAGERY, "RGB photometric interpretation")
        TiffConstants.PhotometricInterpretation.RGB_PALETTE ->
            return verdict(GeoTiffContent.IMAGERY, "palette photometric interpretation")
        TiffConstants.PhotometricInterpretation.CMYK, TiffConstants.PhotometricInterpretation.Y_CB_CR,
        TiffConstants.PhotometricInterpretation.CIE_LAB ->
            return verdict(GeoTiffContent.IMAGERY, "colour photometric interpretation ${dir.photometricInterpretation}")
        // White-is-zero is the scanned-document convention; height fields are never stored inverted.
        TiffConstants.PhotometricInterpretation.WHITE_IS_ZERO ->
            return verdict(GeoTiffContent.IMAGERY, "white-is-zero photometric interpretation")
    }
    dir.colorMap?.let { return verdict(GeoTiffContent.IMAGERY, "colour map") }
    if (dir.alphaBand >= 0) return verdict(GeoTiffContent.IMAGERY, "alpha band")
    // 8 bits span 255 steps — enough for a grayscale picture, useless as terrain in metres.
    if (dir.bitsPerFirstSample == 8) return verdict(GeoTiffContent.IMAGERY, "single 8-bit band")

    if (dir.hasVerticalCrs) return verdict(GeoTiffContent.ELEVATION, "vertical CRS geokey")
    return when (dir.sampleFormat.firstOrNull() ?: TiffConstants.SampleFormat.UNSIGNED) {
        // Float rasters are measurements; nobody ships a float orthophoto.
        TiffConstants.SampleFormat.IEEE_FLOAT ->
            verdict(GeoTiffContent.ELEVATION, "single band of ${dir.bitsPerFirstSample}-bit float samples")
        // Signed integers mean the author expected values below zero, which only heights have.
        TiffConstants.SampleFormat.SIGNED ->
            verdict(GeoTiffContent.ELEVATION, "single band of signed ${dir.bitsPerFirstSample}-bit samples")
        else -> if (dir.noData != null) {
            // DEM voids are marked this way; imagery carries transparency as an alpha band or mask.
            verdict(GeoTiffContent.ELEVATION, "GDAL_NODATA value ${dir.noData} on a single band")
        } else {
            verdict(
                GeoTiffContent.AMBIGUOUS,
                "single unsigned ${dir.bitsPerFirstSample}-bit band with no nodata or vertical CRS"
            )
        }
    }
}

private fun verdict(content: GeoTiffContent, reason: String) = GeoTiffContentVerdict(content, reason)
