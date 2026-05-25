package earth.worldwind.layer.cache

import org.w3c.dom.Window
import org.w3c.fetch.Response
import kotlin.js.Promise

// Cache API external declarations. The browser provides `window.caches` but the kotlin-stdlib
// doesn't include typed bindings for it; minimal externals here cover the surface every
// cache module in this package uses.

internal external interface CacheStorage {
    fun open(cacheName: String): Promise<JsCache>
    fun has(cacheName: String): Promise<Boolean>
    fun delete(cacheName: String): Promise<Boolean>
}

internal external interface JsCache {
    fun match(request: Any): Promise<Response?>
    fun put(request: Any, response: Response): Promise<Unit>
    fun delete(request: Any): Promise<Boolean>
    fun keys(): Promise<Array<Any>>
}

private external interface WindowWithCaches {
    val caches: CacheStorage
}

internal val Window.cacheStorage: CacheStorage
    get() = this.unsafeCast<WindowWithCaches>().caches
