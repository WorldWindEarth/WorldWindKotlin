package earth.worldwind.util

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.jvm.JvmOverloads

/**
 * LRU cache using an intrusive doubly-linked list for O(1) eviction. The list head is the most
 * recently used entry, the tail is the least recently used; eviction walks tail→head until the
 * cache drops below [lowWater] and has room for the requested allocation. [trimToAge] uses the
 * same tail walk and stops at the first entry whose [Entry.lastUsed] crosses the threshold.
 */
open class LruMemoryCache<K, V> @JvmOverloads constructor(
    val capacity: Long, protected val lowWater: Long = (capacity * 0.75).toLong()
) {
    var usedCapacity = 0L
        protected set
    val entryCount get() = entries.size
    protected val entries = mutableMapOf<K, Entry<K, V>>()
    protected open var age = 0L
        get() = ++field // Auto increment cache age on each access to its entries
    // Intrusive LRU list: head is most-recently-used, tail is least-recently-used.
    private var head: Entry<K, V>? = null
    private var tail: Entry<K, V>? = null

    protected open class Entry<K, V>(val key: K, val value: V, var size: Int) {
        var lastUsed = 0L
        internal var prev: Entry<K, V>? = null
        internal var next: Entry<K, V>? = null
    }

    init {
        require(capacity >= 1) {
            logMessage(ERROR, "LruMemoryCache", "constructor", "invalidCapacity")
        }
        require(lowWater in 0 until capacity) {
            logMessage(
                ERROR, "LruMemoryCache", "constructor",
                "The specified low-water value is greater than or equal to the capacity, or less than 1"
            )
        }
    }

    open operator fun get(key: K): V? {
        val entry = entries[key] ?: return null
        entry.lastUsed = age
        moveToHead(entry)
        return entry.value
    }

    open fun put(key: K, value: V, size: Int): V? {
        if (usedCapacity + size > capacity) makeSpace(size)
        val newEntry = Entry(key, value, size)
        newEntry.lastUsed = age
        usedCapacity += newEntry.size
        val oldEntry = entries.put(key, newEntry)
        if (oldEntry != null) {
            usedCapacity -= oldEntry.size
            unlink(oldEntry)
        }
        linkAtHead(newEntry)
        if (oldEntry != null && newEntry.value !== oldEntry.value) {
            entryRemoved(oldEntry.key, oldEntry.value, newEntry.value, false)
            return oldEntry.value
        }
        return null
    }

    open fun remove(key: K) = entries.remove(key)?.run {
        usedCapacity -= size
        unlink(this)
        entryRemoved(key, value, null, false)
        value
    }

    open fun updateSize(key: K, newSize: Int) = entries[key]?.run {
        usedCapacity += newSize - size
        size = newSize
    }

    open fun trimToAge(maxAge: Long): Int {
        var trimmedCapacity = 0
        // Walk tail→head, evicting until we encounter an entry that's young enough to keep.
        var entry = tail
        while (entry != null) {
            if (entry.lastUsed >= maxAge) break
            val prev = entry.prev
            entries.remove(entry.key)
            usedCapacity -= entry.size
            trimmedCapacity += entry.size
            unlink(entry)
            entryRemoved(entry.key, entry.value, null, false)
            entry = prev
        }
        return trimmedCapacity
    }

    open fun containsKey(key: K) = entries.containsKey(key)

    open fun clear() {
        // NOTE Entities cleared without entryRemoved call
        // for (entry in entries.values) entryRemoved(entry.key, entry.value, null, false)
        entries.clear()
        head = null
        tail = null
        usedCapacity = 0
    }

    protected open fun makeSpace(spaceRequired: Int) {
        var entry = tail
        while (entry != null && (usedCapacity > lowWater || capacity - usedCapacity < spaceRequired)) {
            val prev = entry.prev
            entries.remove(entry.key)
            usedCapacity -= entry.size
            unlink(entry)
            entryRemoved(entry.key, entry.value, null, true)
            entry = prev
        }
    }

    private fun moveToHead(entry: Entry<K, V>) {
        if (head === entry) return
        unlink(entry)
        linkAtHead(entry)
    }

    private fun linkAtHead(entry: Entry<K, V>) {
        entry.prev = null
        entry.next = head
        head?.prev = entry
        head = entry
        if (tail == null) tail = entry
    }

    private fun unlink(entry: Entry<K, V>) {
        val p = entry.prev
        val n = entry.next
        if (p != null) p.next = n else head = n
        if (n != null) n.prev = p else tail = p
        entry.prev = null
        entry.next = null
    }

    protected open fun entryRemoved(key: K, oldValue: V, newValue: V?, evicted: Boolean) {}
}
