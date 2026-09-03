package earth.worldwind.render.image

/**
 * A decoded raster in `0xAARRGGBB` layout — the one pixel format every target can hand to
 * its native bitmap type without re-shuffling channels (`BufferedImage.TYPE_INT_ARGB`,
 * Android `Bitmap.Config.ARGB_8888`, a 2D canvas' `ImageData`, a Core Graphics context).
 *
 * Decoders that produce pixels rather than encoded bytes (GeoTIFF tiles, NITF segments)
 * build one of these and let [argbImageSource] carry it across the platform seam.
 */
class ArgbImage(val width: Int, val height: Int, val argb: IntArray)

/**
 * Wrap a lazily produced [ArgbImage] as an [ImageSource]. [factory] runs off the render
 * thread when the image is first needed and may return `null` when the pixels turn out to
 * be unavailable; it may also be called again after a texture eviction, so it must stay
 * repeatable.
 *
 * [key] is the source's cache identity: two image sources built with equal keys are the
 * same texture as far as the render-resource cache is concerned. Pass something stable and
 * comparable (a tile address, say) so a re-created tile reuses its uploaded texture instead
 * of decoding again.
 */
expect fun argbImageSource(key: Any, factory: suspend () -> ArgbImage?): ImageSource

/** Renderer-ready [ImageSource] for pixels that are already decoded. The image itself is the
 *  cache key, so the source is identity-comparable like any other in-memory bitmap. */
fun ArgbImage.toImageSource(): ImageSource = argbImageSource(this) { this }
