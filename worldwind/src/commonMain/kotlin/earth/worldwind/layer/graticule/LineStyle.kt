package earth.worldwind.layer.graticule

/**
 * A line stipple pattern.
 *
 * @param factor  specifies the number of times each bit in the pattern is repeated before the next bit is used. For
 *                example, if the factor is 3, each bit is repeated three times before using the next bit. The
 *                specified factor must be either zero or an integer greater than 0. A factor of 0 indicates no
 *                stippling.
 * @param pattern specifies a number whose lower 16 bits define a pattern of which pixels in the image are white and
 *                which are transparent. Each bit corresponds to a pixel, and the pattern repeats after every n*16
 *                pixels, where n is the factor. For example, if the factor is 3, each bit in the pattern is
 *                repeated three times before using the next bit.
 */
enum class LineStyle(val factor: Int, val pattern: Short) {
    SOLID(0, 0x0000),
    DASHED(1, 0xFFE0.toShort()),
    DOTTED(2, 0xCCCC.toShort()),
    DASH_DOTTED(2, 0xFFCC.toShort())
}