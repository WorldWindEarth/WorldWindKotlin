package earth.worldwind.layer.mvt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import earth.worldwind.render.image.ImageSource

actual class MvtAtlasIconFactory actual constructor(
    actual val atlas: MvtSpriteAtlas,
    actual val entry: MvtSpriteEntry,
) : ImageSource.ImageFactory {

    override suspend fun createBitmap(): Bitmap? {
        val whole = decodedAtlasFor(atlas) ?: return null
        val x = entry.x.coerceIn(0, maxOf(0, whole.width - 1))
        val y = entry.y.coerceIn(0, maxOf(0, whole.height - 1))
        val w = entry.width.coerceAtMost(whole.width - x)
        val h = entry.height.coerceAtMost(whole.height - y)
        if (w <= 0 || h <= 0) return null
        // createBitmap with explicit Config copies pixels into a fresh ARGB_8888 buffer; the
        // engine's texture upload then sees a self-contained bitmap that survives independent
        // GC of the atlas-wide Bitmap.
        return Bitmap.createBitmap(whole, x, y, w, h).copy(Bitmap.Config.ARGB_8888, false)
    }

    actual companion object {
        private val decodeCache = HashMap<MvtSpriteAtlas, Bitmap?>()

        @Synchronized
        private fun decodedAtlasFor(atlas: MvtSpriteAtlas): Bitmap? {
            decodeCache[atlas]?.let { return it }
            val bitmap = BitmapFactory.decodeByteArray(atlas.imageBytes, 0, atlas.imageBytes.size)
            decodeCache[atlas] = bitmap
            return bitmap
        }

        @Synchronized
        actual fun releaseDecodedImage(atlas: MvtSpriteAtlas) { decodeCache.remove(atlas) }
    }
}
