package earth.worldwind.util.js
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.js.unsafeCast

/**
 * Allocate a JS object literal typed as the external [T] and optionally configure its fields via
 * [block]. Replaces the scattered `js("({})")` factory functions (RequestInit, IDB option bags and
 * record surrogates). Kotlin/Wasm requires the bare `js()` call to be a top-level function body,
 * which [newJsObject] provides; `unsafeCast` then reinterprets the empty object as [T].
 *
 * Usage: `jso<RequestInit>()` for an empty object, or `jso<Foo> { bar = baz }` to configure it.
 */
internal inline fun <T : JsAny> jso(block: T.() -> Unit = {}): T = newJsObject().unsafeCast<T>().apply(block)

@PublishedApi
internal fun newJsObject(): JsAny = js("({})")
