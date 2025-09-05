package earth.worldwind.globe.geoid

import dev.icerock.moko.resources.AssetResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

actual open class EGM96Geoid actual constructor(
    offsetsFile: AssetResource, scope: CoroutineScope
) : AbstractEGM96Geoid(offsetsFile, scope) {
    private var deltas: ShortBuffer? = null
    actual override val isInitialized get() = deltas != null

    actual override fun release() {
        super.release()
        deltas = null
    }
    actual override suspend fun loadData(offsetsFile: AssetResource) = withContext(Dispatchers.IO) {
        val bytes = with(offsetsFile) { resourcesClassLoader.getResourceAsStream(filePath)?.use { it.readBytes() } }
            ?: throw FileNotFoundException("Couldn't open resource as stream at: ${offsetsFile.filePath}")
        deltas = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asShortBuffer()
    }

    actual override fun getValue(k: Int) = deltas?.let { if (k in 0..it.limit()) it[k] else 0 } ?: 0

}