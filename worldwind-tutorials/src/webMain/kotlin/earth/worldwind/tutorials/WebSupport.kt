package earth.worldwind.tutorials

import org.w3c.fetch.RequestInit

/**
 * Browser helpers shared by the js and wasmJs tutorial shells. The matching helpers in `:worldwind`
 * are `internal`, so the tutorials module declares its own.
 */

/**
 * Kotlin/Wasm has no `kotlin.js.console`; bind the global console as a typed external so the same
 * code logs on both targets. Named `jsConsole` (not `console`) to avoid clashing with the stdlib
 * `console` that is in scope on the js target.
 */
internal external interface JsConsole : JsAny {
    fun log(message: String)
    fun warn(message: String)
    fun error(message: String)
}
internal val jsConsole: JsConsole = js("console")

/**
 * kotlinx-browser's js `fetch` requires an explicit init argument, but its `RequestInit()` factory
 * sets enum fields (e.g. cache) to null which the browser rejects at runtime — an empty JS object
 * passes the compile-time requirement and lets fetch apply its real defaults.
 */
internal fun emptyRequestInit(): RequestInit = js("({})")
