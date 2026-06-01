package earth.worldwind.layer.ogc3d.content

import earth.worldwind.render.Texture
import earth.worldwind.render.image.ImageDecoder
import earth.worldwind.render.image.ImageTexture

/** UIImage(data:) → CGImage → RGBA via the shared decoder. */
internal actual suspend fun decodeTileTexture(bytes: ByteArray): Texture? {
    val image = TileImageDecoder.decode(bytes) ?: return null
    return ImageTexture(image)
}

/** Shim that exposes the protected `decodeBytes`. */
private object TileImageDecoder : ImageDecoder() {
    fun decode(bytes: ByteArray) = decodeBytes(bytes, 0)
}
