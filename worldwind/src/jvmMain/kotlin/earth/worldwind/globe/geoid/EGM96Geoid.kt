package earth.worldwind.globe.geoid

import dev.icerock.moko.resources.AssetResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

private var deltas: ShortBuffer? = null
actual val isInitialized get() = deltas != null

actual fun loadData(offsetsFile: AssetResource, scope: CoroutineScope) {
    scope.launch {
        val bytes = with(offsetsFile) { resourcesClassLoader.getResourceAsStream(filePath)?.use { it.readBytes() } }
            ?: throw FileNotFoundException("Couldn't open resource as stream at: ${offsetsFile.filePath}")
        deltas = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asShortBuffer()
    }
}

actual fun getValue(k: Int) = deltas?.let { if (k in 0..it.limit()) it[k] else 0 } ?: 0
