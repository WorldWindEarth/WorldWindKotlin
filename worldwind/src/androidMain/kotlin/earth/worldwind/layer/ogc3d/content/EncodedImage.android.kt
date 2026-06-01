package earth.worldwind.layer.ogc3d.content

import android.graphics.BitmapFactory
import earth.worldwind.render.Texture
import earth.worldwind.render.image.BitmapTexture

internal actual suspend fun decodeTileTexture(bytes: ByteArray): Texture? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return BitmapTexture(bitmap)
}
