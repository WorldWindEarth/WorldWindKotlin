package earth.worldwind.formats.nitf

import earth.worldwind.render.image.ArgbImage
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.toImageSource
import earth.worldwind.geom.Sector

/**
 * A decoded NITF image ready to be displayed. Holds a row-major ARGB int[]
 * (alpha in the top byte, then R, G, B) alongside the dimensions and the
 * georeferenced sector recovered from the IGEOLO field.
 *
 * Use [toImageSource] to obtain a platform-specific [ImageSource] that a
 * [earth.worldwind.shape.SurfaceImage] can consume. The per-platform bitmap
 * conversion (`BufferedImage` on JVM, `Bitmap` on Android, canvas on JS,
 * `UIImage` on iOS) lives behind [ArgbImage], shared with every other decoder
 * that produces pixels rather than encoded bytes.
 */
class NitfImage(
    val width: Int,
    val height: Int,
    /** Row-major ARGB pixels in `0xAARRGGBB` layout — same as
     *  `BufferedImage.TYPE_INT_ARGB` and Android `Bitmap.Config.ARGB_8888`. */
    val argb: IntArray,
    /** Georeferenced sector from the image segment's IGEOLO field. */
    val sector: Sector,
)

/**
 * Convert a [NitfImage] into a renderer-ready [ImageSource]. The platform builds
 * the cheapest native bitmap representation it can (no PNG-encode round-trip)
 * and hands it to the surface-image pipeline.
 */
fun NitfImage.toImageSource(): ImageSource = ArgbImage(width, height, argb).toImageSource()
