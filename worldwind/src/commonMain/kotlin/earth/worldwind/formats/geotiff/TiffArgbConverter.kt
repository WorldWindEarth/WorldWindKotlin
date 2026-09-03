package earth.worldwind.formats.geotiff

import kotlin.math.roundToInt

/**
 * Turns one decoded pixel's samples into `0xAARRGGBB`, following the directory's
 * photometric interpretation. Covers the layouts raster imagery actually ships in —
 * grayscale (either polarity), RGB(A), and palette — at any of the sample widths the block
 * decoder produces. CMYK / YCbCr (i.e. JPEG-in-TIFF) is refused rather than guessed at.
 */
internal class ArgbConverter private constructor(
    private val mode: Int,
    private val scale: Float,
    private val alphaBand: Int,
    private val palette: IntArray?,
    private val paletteEntries: Int,
) {
    fun toArgb(samples: FloatArray): Int {
        val alpha = if (alphaBand >= 0 && alphaBand < samples.size) {
            (samples[alphaBand] * scale).roundToInt().coerceIn(0, 255)
        } else 255
        return when (mode) {
            MODE_PALETTE -> {
                val index = samples[0].roundToInt().coerceIn(0, paletteEntries - 1)
                val map = palette ?: return 0
                // ColorMap stores 16-bit components in three consecutive runs.
                val r = map[index] shr 8
                val g = map[paletteEntries + index] shr 8
                val b = map[2 * paletteEntries + index] shr 8
                (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
            MODE_RGB -> {
                val r = component(samples[0])
                val g = component(samples.getOrElse(1) { samples[0] })
                val b = component(samples.getOrElse(2) { samples[0] })
                (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
            MODE_GRAY_INVERTED -> {
                val v = 255 - component(samples[0])
                (alpha shl 24) or (v shl 16) or (v shl 8) or v
            }
            else -> {
                val v = component(samples[0])
                (alpha shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }

    private fun component(value: Float) = (value * scale).roundToInt().coerceIn(0, 255)

    companion object {
        private const val MODE_GRAY = 0
        private const val MODE_GRAY_INVERTED = 1
        private const val MODE_RGB = 2
        private const val MODE_PALETTE = 3

        /** Build a converter for [dir], or `null` when its pixels aren't renderable as colour. */
        fun of(dir: TiffDirectory): ArgbConverter? {
            val bits = dir.bitsPerFirstSample
            val isFloat = dir.sampleFormat.firstOrNull() == TiffConstants.SampleFormat.IEEE_FLOAT
            // Float imagery is conventionally normalized to 0..1; integer imagery spans its
            // full sample width, so 16-bit channels are scaled down to 8-bit display range.
            val scale = if (isFloat) 255f else 255f / ((1L shl bits) - 1L).toFloat()
            val alphaBand = dir.alphaBand
            val mode = when (dir.photometricInterpretation) {
                TiffConstants.PhotometricInterpretation.WHITE_IS_ZERO -> MODE_GRAY_INVERTED
                TiffConstants.PhotometricInterpretation.BLACK_IS_ZERO -> MODE_GRAY
                TiffConstants.PhotometricInterpretation.RGB -> MODE_RGB
                TiffConstants.PhotometricInterpretation.RGB_PALETTE -> MODE_PALETTE
                else -> {
                    dir.warnOnce(
                        "GeoTIFF photometric interpretation " +
                            "${dir.photometricInterpretation} cannot be rendered as imagery"
                    )
                    return null
                }
            }
            var palette: IntArray? = null
            var paletteEntries = 0
            if (mode == MODE_PALETTE) {
                palette = dir.colorMap
                if (palette == null || palette.size < 3) {
                    dir.warnOnce("GeoTIFF palette image has no color map")
                    return null
                }
                paletteEntries = palette.size / 3
            }
            return ArgbConverter(mode, scale, alphaBand, palette, paletteEntries)
        }
    }
}
