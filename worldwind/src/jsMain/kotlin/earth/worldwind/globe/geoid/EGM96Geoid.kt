package earth.worldwind.globe.geoid

import dev.icerock.moko.resources.AssetResource
import dev.icerock.moko.resources.internal.retryIO
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.khronos.webgl.Int16Array
import org.khronos.webgl.get

private var deltas: Int16Array? = null
actual val isInitialized get() = deltas != null

actual fun loadData(offsetsFile: AssetResource, scope: CoroutineScope) {
    scope.launch {
        retryIO {
            deltas = Int16Array(window.fetch(offsetsFile.originalPath).await().arrayBuffer().await())
        }
    }
}

actual fun getValue(k: Int) = deltas?.let { if (k in 0 until it.length) it[k] else 0 } ?: 0