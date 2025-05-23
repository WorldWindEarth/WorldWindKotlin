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
        // NOTE! It is not possible to provide Context here, that's why we are using pure Java resources access
        val filePath = "assets/${offsetsFile.path}"
        val bytes = EGM96Geoid::class.java.classLoader?.getResourceAsStream(filePath)?.use { it.readBytes() }
            ?: throw FileNotFoundException("Couldn't open resource as stream at: $filePath")
        deltas = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asShortBuffer()
    }
}

actual fun getValue(k: Int) = deltas?.get(k) ?: 0
