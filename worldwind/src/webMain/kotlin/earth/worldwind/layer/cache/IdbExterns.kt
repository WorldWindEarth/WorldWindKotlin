package earth.worldwind.layer.cache

import earth.worldwind.util.js.jso
import org.w3c.dom.Window
import org.w3c.dom.events.Event
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsString
import kotlin.js.js
import kotlin.js.toJsString
import kotlin.js.unsafeCast

// Minimal typed surface for the browser IndexedDB API. The kotlin-stdlib doesn't ship an
// IDB binding, so every IDB-backed cache module in this package shares these declarations.

internal external interface IDBFactory : JsAny {
    fun open(name: String, version: Int): IDBOpenDBRequest
}

internal external interface IDBOpenDBRequest : IDBRequest {
    var onupgradeneeded: ((Event) -> Unit)?
    /** Fires when a prior connection at a lower version is still open, blocking the upgrade. */
    var onblocked: ((Event) -> Unit)?
}

internal external interface IDBRequest : JsAny {
    val result: JsAny?
    val error: JsError?
    var onsuccess: ((Event) -> Unit)?
    var onerror: ((Event) -> Unit)?
}

/** Minimal view of a DOMException / Error, just to surface its `message` in log lines. */
internal external interface JsError : JsAny {
    val message: String?
}

internal external interface IDBDatabase : JsAny {
    val name: String
    val objectStoreNames: DOMStringList
    fun createObjectStore(name: String, options: IdbCreateStoreOptions = definedExternally): IDBObjectStore
    fun deleteObjectStore(name: String)
    fun transaction(storeNames: String, mode: String = definedExternally): IDBTransaction
    fun transaction(storeNames: JsArray<JsString>, mode: String = definedExternally): IDBTransaction
    fun close()
}

internal external interface DOMStringList : JsAny {
    val length: Int
    fun contains(name: String): Boolean
}

internal external interface IDBTransaction : JsAny {
    val error: JsError?
    var oncomplete: ((Event) -> Unit)?
    var onerror: ((Event) -> Unit)?
    var onabort: ((Event) -> Unit)?
    fun objectStore(name: String): IDBObjectStore
    // IDB v3 (Chrome 76+, Safari 14+, Firefox 60+). Callers must wrap in runCatching for
    // the InvalidStateError some browsers throw if the tx already auto-committed.
    fun commit()
}

internal external interface IDBObjectStore : JsAny {
    fun add(value: JsAny?): IDBRequest
    fun put(value: JsAny?): IDBRequest
    fun put(value: JsAny?, key: JsAny?): IDBRequest
    fun get(key: JsAny?): IDBRequest
    fun delete(key: JsAny?): IDBRequest
    fun clear(): IDBRequest
    fun count(range: JsAny? = definedExternally): IDBRequest
    fun openCursor(range: JsAny? = definedExternally): IDBRequest
    fun createIndex(name: String, keyPath: JsAny, options: IdbCreateIndexOptions = definedExternally): IDBIndex
    fun index(name: String): IDBIndex
}

internal external interface IDBIndex : JsAny {
    fun getAll(query: JsAny? = definedExternally): IDBRequest
    fun openCursor(query: JsAny? = definedExternally): IDBRequest
}

internal external interface IDBCursor : JsAny {
    val key: JsAny?
    val value: JsAny?
    fun delete(): IDBRequest
    fun `continue`()
}

internal external interface IDBKeyRange : JsAny {
    fun bound(lower: JsAny?, upper: JsAny?, lowerOpen: Boolean = definedExternally, upperOpen: Boolean = definedExternally): IDBKeyRange
    fun only(value: JsAny?): IDBKeyRange
}

internal val idbKeyRange: IDBKeyRange = js("globalThis.IDBKeyRange")

// Option bags for createObjectStore / createIndex. Typed external interfaces built via jso {}
// replace the JS object literals the jsMain twin used inline.
internal external interface IdbCreateStoreOptions : JsAny {
    var keyPath: JsAny?
    var autoIncrement: Boolean
}

internal external interface IdbCreateIndexOptions : JsAny {
    var unique: Boolean
}

internal fun idbStoreOptionsKeyPath(keyPath: String, autoIncrement: Boolean) = jso<IdbCreateStoreOptions> {
    this.keyPath = keyPath.toJsString()
    this.autoIncrement = autoIncrement
}

internal fun idbStoreOptionsKeyPath(keyPath: JsArray<JsString>) = jso<IdbCreateStoreOptions> {
    this.keyPath = keyPath
}

internal fun idbIndexOptions(unique: Boolean) = jso<IdbCreateIndexOptions> {
    this.unique = unique
}

// Build a JsArray<JsAny?> from a vararg of JsAny? elements — used to assemble the
// `[contentKey, z, x, y]` composite IDB keys the stores look up by.
internal fun jsKeyArray(vararg elements: JsAny?): JsArray<JsAny?> {
    val arr = JsArray<JsAny?>()
    for (i in elements.indices) arr[i] = elements[i]
    return arr
}

// Build a JsArray<JsString> string keyPath, e.g. ["contentKey","z","x","y"].
internal fun jsStringArray(vararg elements: String): JsArray<JsString> {
    val arr = JsArray<JsString>()
    for (i in elements.indices) arr[i] = elements[i].toJsString()
    return arr
}

private external interface WindowWithIdb : JsAny {
    val indexedDB: IDBFactory
}

internal val Window.indexedDB: IDBFactory
    get() = this.unsafeCast<WindowWithIdb>().indexedDB
