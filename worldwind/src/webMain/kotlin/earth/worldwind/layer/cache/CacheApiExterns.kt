package earth.worldwind.layer.cache

import org.w3c.dom.Window
import org.w3c.fetch.Response
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsBoolean
import kotlin.js.Promise
import kotlin.js.unsafeCast

// Cache API external declarations. The browser provides `window.caches` but the kotlin-stdlib
// doesn't include typed bindings for it; minimal externals here cover the surface every
// cache module in this package uses.

internal external interface CacheStorage : JsAny {
    fun open(cacheName: String): Promise<JsCache>
    fun has(cacheName: String): Promise<JsBoolean>
    fun delete(cacheName: String): Promise<JsBoolean>
}

internal external interface JsCache : JsAny {
    fun match(request: JsAny): Promise<Response?>
    fun put(request: JsAny, response: Response): Promise<JsAny?>
    fun delete(request: JsAny): Promise<JsBoolean>
    fun keys(): Promise<JsArray<JsAny>>
}

private external interface WindowWithCaches : JsAny {
    val caches: CacheStorage
}

internal val Window.cacheStorage: CacheStorage
    get() = this.unsafeCast<WindowWithCaches>().caches
