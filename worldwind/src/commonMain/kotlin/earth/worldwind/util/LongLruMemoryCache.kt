package earth.worldwind.util

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage

/**
 * LRU memory cache keyed by primitive Long, eliminating per-lookup boxing overhead
 * that occurs with [LruMemoryCache]<Long, V> backed by a generic HashMap.
 *
 * Uses open addressing with linear probing and tombstone compaction on eviction, plus an
 * intrusive doubly-linked list across [Entry] objects for O(1) LRU eviction (head = MRU,
 * tail = LRU). The slot table only resolves keys; eviction order is independent of slot layout
 * and survives rehashes without bookkeeping.
 */
open class LongLruMemoryCache<V : Any>(
    val capacity: Long,
    protected val lowWater: Long = (capacity * 0.75).toLong()
) {
    protected open class Entry<V>(val key: Long, val value: V, var size: Int) {
        var lastUsed = 0L
        internal var prev: Entry<V>? = null
        internal var next: Entry<V>? = null
    }

    private companion object {
        private const val FREE: Byte = 0   // slot never used
        private const val FULL: Byte = 1   // slot occupied
        private const val DEAD: Byte = 2   // slot deleted (tombstone)
    }

    private var tableSize = 64             // always a power of 2
    private var mask = tableSize - 1
    private var slotStatus = ByteArray(tableSize)
    private var slotKeys = LongArray(tableSize)
    @Suppress("UNCHECKED_CAST")
    private var slotEntries: Array<Entry<V>?> = arrayOfNulls<Entry<V>>(tableSize)

    var usedCapacity = 0L
        protected set
    val entryCount get() = liveCount
    private var liveCount = 0
    private var deadCount = 0
    private var ageCounter = 0L
    protected open val age get() = ++ageCounter

    // Intrusive LRU list across Entry objects; survives rehash without slot bookkeeping.
    private var head: Entry<V>? = null
    private var tail: Entry<V>? = null

    init {
        require(capacity >= 1) { logMessage(ERROR, "LongLruMemoryCache", "constructor", "invalidCapacity") }
        require(lowWater in 0 until capacity) {
            logMessage(ERROR, "LongLruMemoryCache", "constructor",
                "The specified low-water value is greater than or equal to the capacity, or less than 1")
        }
    }

    /** Mixes key bits for a well-distributed table index. */
    private fun tableIndex(key: Long): Int {
        var h = key xor (key ushr 30)
        h *= -4658895341751442071L
        h = h xor (h ushr 27)
        return h.toInt() and mask
    }

    open operator fun get(key: Long): V? {
        var i = tableIndex(key)
        while (slotStatus[i] != FREE) {
            if (slotStatus[i] == FULL && slotKeys[i] == key) {
                val e = slotEntries[i]!!
                e.lastUsed = age
                moveToHead(e)
                return e.value
            }
            i = (i + 1) and mask
        }
        return null
    }

    open fun put(key: Long, value: V, size: Int): V? {
        if (usedCapacity + size > capacity) makeSpace(size)
        // Scan for existing key; track first tombstone for potential reuse
        var insertSlot = -1
        var i = tableIndex(key)
        while (slotStatus[i] != FREE) {
            if (slotStatus[i] == FULL && slotKeys[i] == key) {
                val old = slotEntries[i]!!
                val newEntry = Entry(key, value, size).also { it.lastUsed = age }
                slotEntries[i] = newEntry
                usedCapacity += size - old.size
                unlink(old)
                linkAtHead(newEntry)
                if (newEntry.value !== old.value) {
                    entryRemoved(old.key, old.value, newEntry.value, false)
                    return old.value
                }
                return null
            }
            if (slotStatus[i] == DEAD && insertSlot < 0) insertSlot = i
            i = (i + 1) and mask
        }
        val slot = if (insertSlot >= 0) insertSlot else i
        if (slotStatus[slot] == DEAD) deadCount--
        slotStatus[slot] = FULL
        slotKeys[slot] = key
        val entry = Entry(key, value, size).also { it.lastUsed = age }
        slotEntries[slot] = entry
        liveCount++
        usedCapacity += size
        linkAtHead(entry)
        if ((liveCount + deadCount) * 4 > tableSize * 3) rehash(tableSize * 2)
        return null
    }

    open fun remove(key: Long): V? {
        var i = tableIndex(key)
        while (slotStatus[i] != FREE) {
            if (slotStatus[i] == FULL && slotKeys[i] == key) {
                val e = slotEntries[i]!!
                slotStatus[i] = DEAD
                slotEntries[i] = null
                liveCount--
                deadCount++
                usedCapacity -= e.size
                unlink(e)
                entryRemoved(key, e.value, null, false)
                return e.value
            }
            i = (i + 1) and mask
        }
        return null
    }

    open fun containsKey(key: Long): Boolean {
        var i = tableIndex(key)
        while (slotStatus[i] != FREE) {
            if (slotStatus[i] == FULL && slotKeys[i] == key) return true
            i = (i + 1) and mask
        }
        return false
    }

    open fun clear() {
        slotStatus.fill(FREE)
        slotEntries.fill(null)
        liveCount = 0
        deadCount = 0
        usedCapacity = 0L
        head = null
        tail = null
    }

    protected open fun makeSpace(spaceRequired: Int) {
        var entry = tail
        while (entry != null && (usedCapacity > lowWater || capacity - usedCapacity < spaceRequired)) {
            val prev = entry.prev
            evict(entry.key)
            entry = prev
        }
        if (deadCount > tableSize / 8) rehash(tableSize)
    }

    protected open fun entryRemoved(key: Long, oldValue: V, newValue: V?, evicted: Boolean) {}

    /** Like [remove] but fires entryRemoved with evicted=true. */
    private fun evict(key: Long) {
        var i = tableIndex(key)
        while (slotStatus[i] != FREE) {
            if (slotStatus[i] == FULL && slotKeys[i] == key) {
                val e = slotEntries[i]!!
                slotStatus[i] = DEAD
                slotEntries[i] = null
                liveCount--
                deadCount++
                usedCapacity -= e.size
                unlink(e)
                entryRemoved(key, e.value, null, true)
                return
            }
            i = (i + 1) and mask
        }
    }

    private fun moveToHead(entry: Entry<V>) {
        if (head === entry) return
        unlink(entry)
        linkAtHead(entry)
    }

    private fun linkAtHead(entry: Entry<V>) {
        entry.prev = null
        entry.next = head
        head?.prev = entry
        head = entry
        if (tail == null) tail = entry
    }

    private fun unlink(entry: Entry<V>) {
        val p = entry.prev
        val n = entry.next
        if (p != null) p.next = n else head = n
        if (n != null) n.prev = p else tail = p
        entry.prev = null
        entry.next = null
    }

    private fun rehash(newTableSize: Int) {
        val oldStatus = slotStatus
        val oldKeys = slotKeys
        val oldEntries = slotEntries
        tableSize = newTableSize
        mask = tableSize - 1
        slotStatus = ByteArray(tableSize)
        slotKeys = LongArray(tableSize)
        slotEntries = arrayOfNulls(tableSize)
        liveCount = 0
        deadCount = 0
        for (idx in oldStatus.indices) {
            if (oldStatus[idx] == FULL) {
                var i = tableIndex(oldKeys[idx])
                while (slotStatus[i] != FREE) i = (i + 1) and mask
                slotStatus[i] = FULL
                slotKeys[i] = oldKeys[idx]
                slotEntries[i] = oldEntries[idx]
                liveCount++
            }
        }
        // head/tail and Entry.prev/next are untouched — rehash only moves slots, not Entry objects.
    }
}
