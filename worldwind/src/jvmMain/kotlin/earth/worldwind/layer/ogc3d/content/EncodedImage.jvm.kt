package earth.worldwind.layer.ogc3d.content

import earth.worldwind.render.Texture
import earth.worldwind.render.image.ImageTexture
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

internal actual suspend fun decodeTileTexture(bytes: ByteArray): Texture? {
    val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
    return ImageTexture(image)
}
