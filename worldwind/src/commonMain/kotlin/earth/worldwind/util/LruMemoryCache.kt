package earth.worldwind.util

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.jvm.JvmOverloads

open class LruMemoryCache<K, V> @JvmOverloads constructor(
    val capacity: Long, protected val lowWater: Long = (capacity * 0.75).toLong()
) {
    var usedCapacity = 0L
        protected set
    val entryCount get() = entries.size
    // sorts entries from least recently used to most recently used
    protected open val lruComparator = Comparator<Entry<K, V>> { lhs, rhs -> lhs.lastUsed.compareTo(rhs.lastUsed) }
    protected val entries = mutableMapOf<K, Entry<K, V>>()
    protected open var age = 0L
        get() = ++field // Auto increment cache age on each access to its entries

    protected open class Entry<K, V>(val key: K, val value: V, var size: Int) {
        var lastUsed = 0L
        var version = 0L
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

    open operator fun get(key: K) = entries[key]?.run {
        lastUsed = age
        value
    }

    open fun put(key: K, value: V, size: Int): V? {
        if (usedCapacity + size > capacity) makeSpace(size)
        val newEntry = Entry(key, value, size)
        newEntry.lastUsed = age
        usedCapacity += newEntry.size
        val oldEntry = entries.put(key, newEntry)
        if (oldEntry != null) {
            usedCapacity -= oldEntry.size
            if (newEntry.value !== oldEntry.value) {
                entryRemoved(oldEntry.key, oldEntry.value, newEntry.value, false)
                return oldEntry.value
            }
        }
        return null
    }

    open fun remove(key: K) = entries.remove(key)?.run {
        usedCapacity -= size
        entryRemoved(key, value, null, false)
        value
    }

    open fun update(key: K, newVersion: Long, update: () -> Int) = entries[key]?.run {
        if (version < newVersion) {
            version = newVersion
            val newSize = update()
            usedCapacity += newSize - size
            size = newSize
        }
    }

    open fun trimToAge(maxAge: Long): Int {
        var trimmedCapacity = 0

        // Sort the entries from least recently used to most recently used.
        val sortedEntries = assembleSortedEntries()

        // Remove the least recently used entries until the entry's age is within the specified maximum age.
        for (i in sortedEntries.indices) {
            val entry = sortedEntries[i]
            if (entry.lastUsed < maxAge) {
                entries.remove(entry.key)
                usedCapacity -= entry.size
                trimmedCapacity += entry.size
                entryRemoved(entry.key, entry.value, null, false)
            } else break
        }
        return trimmedCapacity
    }

    open fun containsKey(key: K) = entries.containsKey(key)

    open fun clear() {
        // NOTE Entities cleared without entryRemoved call
        // for (entry in entries.values) entryRemoved(entry.key, entry.value, null, false)
        entries.clear()
        usedCapacity = 0
    }

    protected open fun makeSpace(spaceRequired: Int) {
        // Sort the entries from least recently used to most recently used.
        val sortedEntries = assembleSortedEntries()

        // Remove the least recently used entries until the cache capacity reaches the low water and the cache has
        // enough free capacity for the required space.
        for (i in sortedEntries.indices) {
            val entry = sortedEntries[i]
            if (usedCapacity > lowWater || capacity - usedCapacity < spaceRequired) {
                entries.remove(entry.key)
                usedCapacity -= entry.size
                entryRemoved(entry.key, entry.value, null, true)
            } else break
        }
    }

    /*
     * Sort the entries from least recently used to most recently used.
     */
    protected open fun assembleSortedEntries() = entries.values.sortedWith(lruComparator)

    protected open fun entryRemoved(key: K, oldValue: V, newValue: V?, evicted: Boolean) {}
}