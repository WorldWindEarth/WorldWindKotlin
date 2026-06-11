package earth.worldwind.formats.gltf.ktx2

import earth.worldwind.formats.gltf.GltfDecoderRegistry
import earth.worldwind.formats.gltf.Ktx2Decoder
import earth.worldwind.formats.gltf.Ktx2Image
import kotlinx.coroutines.await
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.js

/** Web actual. Loads Google's `basis_transcoder` WASM (shipped by THREE.js at
 *  `three/examples/jsm/libs/basis/`) and registers a [Ktx2Decoder] that pipes
 *  KTX2 / Basis-Universal byte streams through its `KTX2File` JS class. Consumer-side
 *  webpack config required — see worldwind-formats-gltf-ktx2/README.md. Idempotent. */
actual suspend fun installKtx2Decoder() {
    if (GltfDecoderRegistry.ktx2Decoder != null) return
    NativeKtx2.ensureInitialized()
    GltfDecoderRegistry.ktx2Decoder = WebKtx2Decoder
}

actual fun releaseKtx2Decoder() {
    if (GltfDecoderRegistry.ktx2Decoder === WebKtx2Decoder) {
        GltfDecoderRegistry.ktx2Decoder = null
    }
}

private object WebKtx2Decoder : Ktx2Decoder {
    override fun decode(ktx2Bytes: ByteArray): Ktx2Image {
        if (ktx2Bytes.isEmpty()) throw IllegalArgumentException("KTX2 decode: empty input")
        val u8 = ktx2Bytes.toUint8Array()
        val result = NativeKtx2.decode(u8)
            ?: throw IllegalArgumentException("KTX2 decode: basis transcoder rejected input")
        return Ktx2Image(rgba = result.rgba.toByteArray(), width = result.width, height = result.height)
    }
}

internal object NativeKtx2 {

    // Resolved basis module after the WASM finishes loading. Single-threaded JS — plain
    // var is fine.
    private var basisModule: JsAny? = null

    suspend fun ensureInitialized() {
        if (basisModule != null) return
        basisModule = createBasisModule().await()
    }

    internal class Decoded(val rgba: Uint8Array, val width: Int, val height: Int)

    fun decode(bytes: Uint8Array): Decoded? {
        val m = basisModule ?: return null
        return decodeInJs(m, bytes)?.let { result ->
            Decoded(
                rgba = result.uint8Array("rgba"),
                width = result.int("width"),
                height = result.int("height"),
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────────────
// JS interop. Each js() body is a JS function with the surrounding Kotlin parameter
// names bound as arguments.
// ──────────────────────────────────────────────────────────────────────────────────────

/** basis_transcoder.js exposes its factory on `globalThis.BASIS` (Emscripten default
 *  EXPORT_NAME), not via module.exports. Webpack emits it as an asset/resource so the
 *  require() result is the URL string; we script-inject and read the factory off the
 *  global, then hand the WASM URL to Emscripten via locateFile. */
private fun createBasisModule(): Promise<JsAny> = js(
    """
    var basisJsUrl = require('three/examples/jsm/libs/basis/basis_transcoder.js');
    var basisWasmUrl = require('three/examples/jsm/libs/basis/basis_transcoder.wasm');
    return new Promise(function(resolve, reject) {
        var script = document.createElement('script');
        script.src = basisJsUrl;
        script.onload = function() {
            var factory = globalThis.BASIS || globalThis.BasisModule || globalThis.Module;
            if (typeof factory !== 'function') {
                reject(new Error(
                    'basis_transcoder.js loaded but no factory found on globalThis ' +
                    '(looked for BASIS / BasisModule / Module). THREE version mismatch?'
                ));
                return;
            }
            factory({
                locateFile: function(name) {
                    return name.endsWith('.wasm') ? basisWasmUrl : name;
                }
            }).then(function(module) {
                module.initializeBasis();
                resolve(module);
            }).catch(reject);
        };
        script.onerror = function() {
            reject(new Error('Failed to load basis_transcoder.js from ' + basisJsUrl));
        };
        document.head.appendChild(script);
    });
    """
)

/** Decode the base mip of a KTX2 container into RGBA8888. Returns
 *  { rgba: Uint8Array, width: Int, height: Int } on success, null on failure. The
 *  KTX2File JS instance is `delete()`-d in every exit path so the Emscripten heap
 *  stays clean. */
@Suppress("UNUSED_PARAMETER")
private fun decodeInJs(m: JsAny, bytes: Uint8Array): JsAny? = js(
    """
    var ktx = null;
    try {
        ktx = new m.KTX2File(bytes);
        if (!ktx.isValid()) { ktx.close(); ktx.delete(); return null; }
        if (!ktx.startTranscoding()) { ktx.close(); ktx.delete(); return null; }
        var width = ktx.getWidth();
        var height = ktx.getHeight();
        // 13 = transcoder_texture_format::cTFRGBA32 — uncompressed RGBA8888 output
        // regardless of source encoding (ETC1S, UASTC, etc.), matching the JVM/Android
        // Ktx2Decoder contract.
        var FMT_RGBA32 = 13;
        var byteCount = ktx.getImageTranscodedSizeInBytes(0, 0, 0, FMT_RGBA32);
        if (byteCount <= 0) { ktx.close(); ktx.delete(); return null; }
        var rgba = new Uint8Array(byteCount);
        var ok = ktx.transcodeImage(rgba, 0, 0, 0, FMT_RGBA32, 0, -1, -1);
        ktx.close(); ktx.delete();
        if (!ok) return null;
        return { rgba: rgba, width: width, height: height };
    } catch (e) {
        if (ktx) { try { ktx.close(); } catch(_) {}; try { ktx.delete(); } catch(_) {} }
        return null;
    }
    """
)

@Suppress("UNUSED_PARAMETER")
private fun JsAny.uint8Array(field: String): Uint8Array = readUint8Array(this, field)
    ?: Uint8Array(0)

@Suppress("UNUSED_PARAMETER")
private fun JsAny.int(field: String): Int = readInt(this, field)

@Suppress("UNUSED_PARAMETER")
private fun readUint8Array(obj: JsAny, field: String): Uint8Array? = js("obj[field]")

@Suppress("UNUSED_PARAMETER")
private fun readInt(obj: JsAny, field: String): Int = js("obj[field]")

private fun ByteArray.toUint8Array(): Uint8Array {
    val u8 = Uint8Array(size)
    for (i in indices) u8[i] = this[i]
    return u8
}

private fun Uint8Array.toByteArray(): ByteArray {
    val out = ByteArray(length)
    for (i in out.indices) out[i] = this[i]
    return out
}
