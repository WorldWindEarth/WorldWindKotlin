package earth.worldwind.layer.mvt

import earth.worldwind.render.image.ImageSource
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

actual class MvtAtlasIconFactory actual constructor(
    actual val atlas: MvtSpriteAtlas,
    actual val entry: MvtSpriteEntry,
) : ImageSource.ImageFactory {

    override suspend fun createImage(): BufferedImage? {
        val whole = decodedAtlasFor(atlas) ?: return null
        val x = entry.x.coerceIn(0, maxOf(0, whole.width - 1))
        val y = entry.y.coerceIn(0, maxOf(0, whole.height - 1))
        val w = entry.width.coerceAtMost(whole.width - x)
        val h = entry.height.coerceAtMost(whole.height - y)
        if (w <= 0 || h <= 0) return null
        // getSubimage shares pixel data with the parent — copy into a fresh image so the
        // engine's texture upload sees a contiguous RGBA region that survives independent
        // GC of the atlas-wide BufferedImage.
        val sub = whole.getSubimage(x, y, w, h)
        val copy = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        copy.createGraphics().apply {
            drawImage(sub, 0, 0, null)
            dispose()
        }
        return copy
    }

    companion object {
        // Cache the decoded atlas keyed by atlas-identity. Each MvtSpriteAtlas is constructed
        // once per sprite endpoint, so identity equality is correct and we don't need to hash
        // the (potentially large) byte array.
        private val decodeCache = HashMap<MvtSpriteAtlas, BufferedImage?>()

        @Synchronized
        private fun decodedAtlasFor(atlas: MvtSpriteAtlas): BufferedImage? {
            decodeCache[atlas]?.let { return it }
            val image = try {
                ImageIO.read(ByteArrayInputStream(atlas.imageBytes))
            } catch (_: Exception) {
                null
            }
            decodeCache[atlas] = image
            return image
        }
    }
}
