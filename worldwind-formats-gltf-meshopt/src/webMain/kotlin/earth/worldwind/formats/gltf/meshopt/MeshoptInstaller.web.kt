package earth.worldwind.formats.gltf.meshopt

import earth.worldwind.formats.gltf.GltfDecoderRegistry
import earth.worldwind.formats.gltf.MeshoptDecoder
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.js
import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

/**
 * Web actual. Wraps the official `meshoptimizer` npm package, which ships a wasm-backed
 * `MeshoptDecoder` object with synchronous decode methods. The factory is async because
 * the wasm module loads lazily; subsequent decodes run synchronously off the cached
 * module.
 */
actual suspend fun installMeshoptDecoder() {
    if (GltfDecoderRegistry.meshoptDecoder != null) return
    awaitMeshoptReady()
    GltfDecoderRegistry.meshoptDecoder = WebMeshoptDecoder
}

actual fun releaseMeshoptDecoder() {
    if (GltfDecoderRegistry.meshoptDecoder === WebMeshoptDecoder) {
        GltfDecoderRegistry.meshoptDecoder = null
    }
}

private object WebMeshoptDecoder : MeshoptDecoder {
    override fun decode(
        compressed: ByteArray,
        count: Int,
        byteStride: Int,
        mode: MeshoptDecoder.Mode,
        filter: MeshoptDecoder.Filter?,
    ): ByteArray {
        val src = compressed.toUint8Array()
        val dst = decodeInJs(src, count, byteStride, mode.ordinal, filter?.ordinal ?: -1)
            ?: error("Meshopt decode failed in JS")
        val n = dst.length
        return ByteArray(n) { dst[it] }
    }
}

/** meshoptimizer's wasm module is lazy-loaded; `MeshoptDecoder.ready` is the Promise we
 *  await before the first decode. */
private suspend fun awaitMeshoptReady() {
    meshoptReadyPromise().await()
}

private fun meshoptReadyPromise(): Promise<JsAny> = js("require('meshoptimizer').MeshoptDecoder.ready")

@Suppress("UNUSED_PARAMETER")
private fun decodeInJs(src: Uint8Array, count: Int, byteStride: Int, mode: Int, filter: Int): Int8Array? = js(
    """
    var d = require('meshoptimizer').MeshoptDecoder;
    var size = count * byteStride;
    var dst = new Uint8Array(size);
    // meshoptimizer's decode* functions throw on a corrupted stream; wrap so callers see
    // null instead of the JS error percolating out the wasm boundary.
    try {
        if (mode === 0) d.decodeVertexBuffer(dst, count, byteStride, src);
        else if (mode === 1) d.decodeIndexBuffer(dst, count, byteStride, src);
        else if (mode === 2) d.decodeIndexSequence(dst, count, byteStride, src);
        else return null;
        // Filter names per meshoptimizer JS API are uppercase: 'OCTAHEDRAL' / 'QUATERNION' / 'EXPONENTIAL'.
        if (filter === 0) d.decodeFilter(dst, count, byteStride, 'OCTAHEDRAL');
        else if (filter === 1) d.decodeFilter(dst, count, byteStride, 'QUATERNION');
        else if (filter === 2) d.decodeFilter(dst, count, byteStride, 'EXPONENTIAL');
    } catch (e) {
        return null;
    }
    return new Int8Array(dst.buffer);
    """
)

private fun ByteArray.toUint8Array(): Uint8Array {
    val u8 = Uint8Array(size)
    for (i in indices) u8[i] = this[i]
    return u8
}
