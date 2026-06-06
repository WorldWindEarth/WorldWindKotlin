package earth.worldwind.layer.cache

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.toJsNumber
import kotlin.js.toJsString

// Shared IndexedDB helpers used across the cache stores (tile / feature / elevation).

// Composite IDB key [contentKey, z, x, y]; the index/store look-ups all key by this shape.
internal fun tileKey(contentKey: String, z: Int, x: Int, y: Int): JsArray<JsAny?> =
    jsKeyArray(contentKey.toJsString(), z.toJsNumber(), x.toJsNumber(), y.toJsNumber())

internal fun ByteArray.toUint8Array(): Uint8Array {
    // Kotlin/Wasm `ByteArray` is not a JS typed array, so copy element by element into a fresh
    // Uint8Array (no zero-copy bridge for the Wasm linear-memory representation). IDB stores the
    // typed array natively without JSON.
    val u8 = Uint8Array(size)
    for (i in indices) u8[i] = this[i]
    return u8
}

// Reverse of [toUint8Array]: copy the typed array's bytes into a Kotlin ByteArray.
internal fun Uint8Array.toByteArray(): ByteArray = ByteArray(length) { this[it] }
