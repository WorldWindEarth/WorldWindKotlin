package earth.worldwind.layer.cache

import org.w3c.dom.Window
import org.w3c.dom.events.Event

// Minimal typed surface for the browser IndexedDB API. The kotlin-stdlib doesn't ship an
// IDB binding, so every IDB-backed cache module in this package shares these declarations.

internal external interface IDBFactory {
    fun open(name: String, version: Int): IDBOpenDBRequest
}

internal external interface IDBOpenDBRequest : IDBRequest {
    var onupgradeneeded: ((Event) -> Unit)?
    /** Fires when a prior connection at a lower version is still open, blocking the upgrade. */
    var onblocked: ((Event) -> Unit)?
}

internal external interface IDBRequest {
    val result: Any?
    val error: dynamic
    var onsuccess: ((Event) -> Unit)?
    var onerror: ((Event) -> Unit)?
}

internal external interface IDBDatabase {
    val name: String
    val objectStoreNames: DOMStringList
    fun createObjectStore(name: String, options: dynamic = definedExternally): IDBObjectStore
    fun deleteObjectStore(name: String)
    fun transaction(storeNames: String, mode: String = definedExternally): IDBTransaction
    fun transaction(storeNames: Array<String>, mode: String = definedExternally): IDBTransaction
    fun close()
}

internal external interface DOMStringList {
    val length: Int
    fun contains(name: String): Boolean
}

internal external interface IDBTransaction {
    val error: dynamic
    var oncomplete: ((Event) -> Unit)?
    var onerror: ((Event) -> Unit)?
    var onabort: ((Event) -> Unit)?
    fun objectStore(name: String): IDBObjectStore
    // IDB v3 (Chrome 76+, Safari 14+, Firefox 60+). Callers must wrap in runCatching for
    // the InvalidStateError some browsers throw if the tx already auto-committed.
    fun commit()
}

internal external interface IDBObjectStore {
    fun add(value: Any?): IDBRequest
    fun put(value: Any?): IDBRequest
    fun put(value: Any?, key: Any?): IDBRequest
    fun get(key: Any?): IDBRequest
    fun delete(key: Any?): IDBRequest
    fun clear(): IDBRequest
    fun count(range: Any? = definedExternally): IDBRequest
    fun openCursor(range: Any? = definedExternally): IDBRequest
    fun createIndex(name: String, keyPath: Any, options: dynamic = definedExternally): IDBIndex
    fun index(name: String): IDBIndex
}

internal external interface IDBIndex {
    fun getAll(query: Any? = definedExternally): IDBRequest
    fun openCursor(query: Any? = definedExternally): IDBRequest
}

internal external interface IDBCursor {
    val key: Any?
    val value: dynamic
    fun delete(): IDBRequest
    fun `continue`()
}

internal external interface IDBKeyRange
internal external object IDBKeyRangeCompanion : IDBKeyRange {
    fun bound(lower: Any?, upper: Any?, lowerOpen: Boolean = definedExternally, upperOpen: Boolean = definedExternally): IDBKeyRange
    fun only(value: Any?): IDBKeyRange
}

internal val idbKeyRange: dynamic get() = js("globalThis.IDBKeyRange")

private external interface WindowWithIdb {
    val indexedDB: IDBFactory
}

internal val Window.indexedDB: IDBFactory
    get() = this.unsafeCast<WindowWithIdb>().indexedDB
