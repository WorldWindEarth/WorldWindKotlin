package earth.worldwind.layer.ogc3d.content

import earth.worldwind.render.Texture

/** Off-thread decode of a glTF-embedded image (JPEG / PNG / WebP) into a [Texture]. */
internal expect suspend fun decodeTileTexture(bytes: ByteArray): Texture?
