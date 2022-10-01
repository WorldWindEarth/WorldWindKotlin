package earth.worldwind.util

import kotlin.math.max

@Suppress("UNCHECKED_CAST")
open class BasicPool<T>: Pool<T> {
    companion object {
        protected const val MIN_CAPACITY_INCREMENT = 12
    }

    protected var size = 0
    protected var entries = arrayOfNulls<Any?>(size)

    override fun acquire(): T? {
        if (size > 0) {
            val last = --size
            val instance = entries[last]
            entries[last] = null
            return instance as T?
        }
        return null
    }

    override fun release(instance: T?) {
        // TODO reduce the pool size when excess entries may not be needed
        // TODO use a keep alive time to indicate how long to keep stale instances
        if (instance != null) {
            val capacity = entries.size
            if (capacity == size) {
                // increase the pool size by the larger of 50% or the minimum increment
                val increment = max(capacity shr 1, MIN_CAPACITY_INCREMENT)
                val newEntries = arrayOfNulls<Any?>(capacity + increment)
                entries.copyInto(newEntries, 0, 0, capacity)
                entries = newEntries
            }
            entries[size++] = instance
        }
    }
}