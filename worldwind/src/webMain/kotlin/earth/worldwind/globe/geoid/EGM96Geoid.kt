package earth.worldwind.globe.geoid

import dev.icerock.moko.resources.AssetResource
import dev.icerock.moko.resources.internal.retryIO
import earth.worldwind.util.js.jso
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int16Array
import org.khronos.webgl.get
import kotlin.js.js

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
        retryIO {
            deltas = int16ArrayOf(window.fetch(offsetsFile.originalPath, jso()).await().arrayBuffer().await())
        }
    }

    actual override fun getValue(k: Int) = deltas?.let { if (k in 0 until it.length) it[k] else 0 } ?: 0
}

// fetch needs an explicit init arg on kotlinx-browser-js, but its RequestInit() sets enum fields
// (e.g. cache) to null which the browser rejects at runtime — jso() builds an empty object that
// lets fetch apply its real defaults.

// View the offsets buffer as an Int16Array. Top-level js() helper (Kotlin/Wasm requires js()
// bodies at file scope; the param binds by name).
@Suppress("UNUSED_PARAMETER") private fun int16ArrayOf(buffer: ArrayBuffer): Int16Array = js("new Int16Array(buffer)")