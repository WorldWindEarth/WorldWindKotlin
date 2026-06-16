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
            val buf = window.fetch(offsetsFile.originalPath, jso()).await().arrayBuffer().await()
            deltas = bigEndianInt16ArrayOf(buf)
        }
    }

    actual override fun getValue(k: Int) = deltas?.let { if (k in 0 until it.length) it[k] else 0 } ?: 0
}

// fetch needs an explicit init arg on kotlinx-browser-js, but its RequestInit() sets enum fields
// (e.g. cache) to null which the browser rejects at runtime — jso() builds an empty object that
// lets fetch apply its real defaults.

// EGM96 ships as INTEGER*2 unformatted Fortran direct-access — big-endian per the NGA spec;
// JS typed-array views are platform-native (little-endian on x86/ARM), so the raw bytes must
// be byte-swapped in place before being viewed as Int16. Top-level js() helper (Kotlin/Wasm
// requires js() bodies at file scope; the param binds by name).
@Suppress("UNUSED_PARAMETER")
private fun bigEndianInt16ArrayOf(buffer: ArrayBuffer): Int16Array = js(
    "(function(){const u=new Uint8Array(buffer);for(let i=0;i+1<u.length;i+=2){const t=u[i];u[i]=u[i+1];u[i+1]=t;}return new Int16Array(buffer);})()"
)