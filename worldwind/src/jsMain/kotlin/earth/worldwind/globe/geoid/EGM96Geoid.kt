package earth.worldwind.globe.geoid

import dev.icerock.moko.resources.AssetResource
import dev.icerock.moko.resources.internal.retryIO
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import org.khronos.webgl.Int16Array
import org.khronos.webgl.get

actual open class EGM96Geoid actual constructor(
    offsetsFile: AssetResource, scope: CoroutineScope
) : AbstractEGM96Geoid(offsetsFile, scope) {
    private var deltas: Int16Array? = null
    actual override val isInitialized get() = deltas != null

    actual override fun release() {
        super.release()
        deltas = null
    }

    actual override suspend fun loadData(offsetsFile: AssetResource) {
        retryIO { deltas = Int16Array(window.fetch(offsetsFile.originalPath).await().arrayBuffer().await()) }
    }

    actual override fun getValue(k: Int) = deltas?.let { if (k in 0 until it.length) it[k] else 0 } ?: 0
}