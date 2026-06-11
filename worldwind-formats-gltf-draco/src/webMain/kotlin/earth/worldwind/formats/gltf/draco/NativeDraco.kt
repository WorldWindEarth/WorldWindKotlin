package earth.worldwind.formats.gltf.draco

import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.js
import kotlinx.coroutines.await
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int32Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

/**
 * Browser-side binding to Google's `draco3d` npm package (loads `draco_decoder.wasm` from
 * the bundled CDN). Same external shape as the JVM / Android / iOS `NativeDraco` objects
 * so `DracoInstaller.web.kt`'s actual can stay close to its siblings.
 *
 * Initialization is one-shot async: the npm package factory returns a Promise that
 * resolves to a decoder module, and we cache the resolved module for subsequent decodes.
 * `ensureInitialized()` is suspend; further `decode()` calls run synchronously against
 * the cached module.
 */
internal object NativeDraco {

    // Single-threaded JS runtime — plain var is fine.
    private var dracoModule: JsAny? = null

    /** Load draco3d's wasm decoder module. Idempotent. */
    suspend fun ensureInitialized() {
        if (dracoModule != null) return
        dracoModule = createDecoderModule().await()
    }

    /** Decode a Draco-compressed bufferView. Returns null when the module isn't initialized
     *  or the decode failed in JS; callers should treat the result as "primitive skipped".
     *  [colorsUniqueId] is the Draco attribute unique-id for colors (PNTS
     *  `3DTILES_draco_point_compression.properties[RGB|RGBA]`); pass -1 for no hint. */
    fun decode(bytes: ByteArray, colorsUniqueId: Int = -1): Decoded? {
        val m = dracoModule ?: return null
        val u8 = bytes.toUint8Array()
        return decodeInJs(m, u8, colorsUniqueId)?.let { result ->
            Decoded(
                indices = result.intArray("indices"),
                positions = result.floatArray("positions"),
                texCoords = result.floatArray("texCoords"),
                colors = result.floatArray("colors"),
            )
        }
    }

    internal data class Decoded(
        val indices: IntArray,
        val positions: FloatArray,
        val texCoords: FloatArray,
        val colors: FloatArray,
    ) {
        override fun equals(other: Any?): Boolean = this === other || (other is Decoded &&
            indices.contentEquals(other.indices) &&
            positions.contentEquals(other.positions) &&
            texCoords.contentEquals(other.texCoords) &&
            colors.contentEquals(other.colors))
        override fun hashCode(): Int = 31 * (31 * (31 * indices.contentHashCode() +
            positions.contentHashCode()) + texCoords.contentHashCode()) + colors.contentHashCode()
    }
}

// ──────────────────────────────────────────────────────────────────────────────────────
// JS / Wasm interop helpers. All multi-line js() bodies live below — Kotlin/Wasm parses
// the body string as a JS function with parameters bound to the surrounding Kotlin
// function's argument names.
// ──────────────────────────────────────────────────────────────────────────────────────

/** Returns Promise<DracoDecoderModule>. Hands the webpack-resolved `.wasm` URL to
 *  Emscripten's `locateFile` so the loader fetches it from the bundle output instead
 *  of guessing the page root. Consumer-side webpack config is required — see
 *  worldwind-formats-gltf-draco/README.md. */
private fun createDecoderModule(): Promise<JsAny> = js(
    """
    var wasmUrl = require('draco3d/draco_decoder.wasm');
    return require('draco3d').createDecoderModule({
        locateFile: function(name) {
            return name.endsWith('.wasm') ? wasmUrl : name;
        }
    });
    """
)

/**
 * Run the full Draco decode pipeline in JS. Returns an object with the four typed-array
 * fields (positions, texCoords, colors, indices) or null on decode failure. The JS code
 * is responsible for `destroy()`-ing every transient libdraco object it allocates so the
 * Emscripten heap stays clean.
 */
@Suppress("UNUSED_PARAMETER")
private fun decodeInJs(m: JsAny, bytes: Uint8Array, colorsUniqueId: Int): JsAny? = js(
    """
    var buffer = new m.DecoderBuffer();
    buffer.Init(bytes, bytes.byteLength);
    var decoder = new m.Decoder();
    var geomType = decoder.GetEncodedGeometryType(buffer);
    var mesh, status;
    if (geomType === m.TRIANGULAR_MESH) {
        mesh = new m.Mesh();
        status = decoder.DecodeBufferToMesh(buffer, mesh);
    } else {
        mesh = new m.PointCloud();
        status = decoder.DecodeBufferToPointCloud(buffer, mesh);
    }
    if (!status.ok()) {
        m.destroy(buffer); m.destroy(decoder); m.destroy(mesh);
        return null;
    }
    function pullAttr(attr, components) {
        var pcount = mesh.num_points();
        var actual = attr.num_components();
        var out = new m.DracoFloat32Array();
        decoder.GetAttributeFloatForAllPoints(mesh, attr, out);
        var arr = new Float32Array(pcount * components);
        // Draco fills `pcount * actual` floats; mirror the JVM bridge's per-point copy +
        // alpha-pad so an RGB color attribute requested as RGBA doesn't shift channels.
        var copy = actual < components ? actual : components;
        for (var p = 0; p < pcount; p++) {
            var dst = p * components;
            var src = p * actual;
            for (var c = 0; c < copy; c++) arr[dst + c] = out.GetValue(src + c);
            for (var c = copy; c < components; c++) arr[dst + c] = (c === 3 && components === 4) ? 1.0 : 0.0;
        }
        m.destroy(out);
        return arr;
    }
    function getAttr(semantic, components) {
        var id = decoder.GetAttributeId(mesh, m[semantic]);
        if (id < 0) return new Float32Array(0);
        return pullAttr(decoder.GetAttribute(mesh, id), components);
    }
    function getByUniqueId(uid, components) {
        if (uid < 0) return new Float32Array(0);
        var attr = decoder.GetAttributeByUniqueId(mesh, uid);
        if (!attr) return new Float32Array(0);
        return pullAttr(attr, components);
    }
    function getGenericAttr(components) {
        // Last-ditch fallback: scan generic attributes when neither uid nor COLOR matched.
        var n = mesh.num_attributes();
        for (var i = 0; i < n; i++) {
            var a = decoder.GetAttribute(mesh, i);
            if (a.attribute_type() !== m.GENERIC) continue;
            var cc = a.num_components();
            if (cc !== 3 && cc !== 4) continue;
            return pullAttr(a, components);
        }
        return new Float32Array(0);
    }
    var positions = getAttr('POSITION', 3);
    var texCoords = getAttr('TEX_COORD', 2);
    var colors = getByUniqueId(colorsUniqueId, 4);
    if (colors.length === 0) colors = getAttr('COLOR', 4);
    if (colors.length === 0) colors = getGenericAttr(4);
    var indices;
    if (geomType === m.TRIANGULAR_MESH) {
        var idxArr = new m.DracoInt32Array();
        decoder.GetTrianglesUInt32Array(mesh, mesh.num_faces() * 3 * 4, idxArr);
        indices = new Int32Array(mesh.num_faces() * 3);
        for (var i = 0; i < indices.length; i++) indices[i] = idxArr.GetValue(i);
        m.destroy(idxArr);
    } else {
        indices = new Int32Array(0);
    }
    m.destroy(buffer); m.destroy(decoder); m.destroy(mesh);
    return { positions: positions, texCoords: texCoords, colors: colors, indices: indices };
    """
)

/** Per-field reader for the JS object returned by [decodeInJs]. */
@Suppress("UNUSED_PARAMETER")
private fun JsAny.floatArray(field: String): FloatArray {
    val arr = readFloat32Array(this, field) ?: return FloatArray(0)
    return FloatArray(arr.length) { arr[it] }
}

@Suppress("UNUSED_PARAMETER")
private fun JsAny.intArray(field: String): IntArray {
    val arr = readInt32Array(this, field) ?: return IntArray(0)
    return IntArray(arr.length) { arr[it] }
}

@Suppress("UNUSED_PARAMETER")
private fun readFloat32Array(obj: JsAny, field: String): Float32Array? = js("obj[field]")

@Suppress("UNUSED_PARAMETER")
private fun readInt32Array(obj: JsAny, field: String): Int32Array? = js("obj[field]")

private fun ByteArray.toUint8Array(): Uint8Array {
    val u8 = Uint8Array(size)
    for (i in indices) u8[i] = this[i]
    return u8
}
