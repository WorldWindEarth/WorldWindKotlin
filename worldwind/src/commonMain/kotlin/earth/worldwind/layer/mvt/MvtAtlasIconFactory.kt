package earth.worldwind.layer.mvt

import earth.worldwind.render.image.ImageSource

/**
 * Platform-specific [ImageSource.ImageFactory] that decodes the atlas PNG once (cached by
 * atlas-identity) and crops the requested sub-rect on demand.
 *
 * JVM uses `javax.imageio.ImageIO` + `BufferedImage.getSubimage`; Android uses
 * `BitmapFactory.decodeByteArray` + `Bitmap.createBitmap`. iOS and JS implementations are
 * stubs that log a one-time warning and return null — icons will be silently skipped on
 * those platforms until the full crop path lands. (Full implementations require platform
 * image-decoder integration that's outside the scope of this commit.)
 */
expect class MvtAtlasIconFactory(atlas: MvtSpriteAtlas, entry: MvtSpriteEntry) : ImageSource.ImageFactory {
    val atlas: MvtSpriteAtlas
    val entry: MvtSpriteEntry
}
