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
        // NOTE! It is not possible to provide Context here, that's why we are using pure Java resources access
        val filePath = "assets/${offsetsFile.path}"
        val bytes = AbstractEGM96Geoid::class.java.classLoader?.getResourceAsStream(filePath)?.use { it.readBytes() }
            ?: throw FileNotFoundException("Couldn't open resource as stream at: $filePath")
        deltas = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asShortBuffer()
    }

    actual override fun getValue(k: Int) = deltas?.let { if (k in 0..it.limit()) it[k] else 0 } ?: 0
}
