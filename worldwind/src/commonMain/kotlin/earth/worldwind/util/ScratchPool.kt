package earth.worldwind.util

/**
 * Sequential scratch pool: hand out [T] instances in order during a single assembly pass, then
 * [reset] the cursor at the start of the next pass to reuse the same instances. Distinct from
 * [BasicPool] which is acquire/release-style — here callers don't release individually because
 * they keep the acquired instances alive in a result list until the entire pass completes.
 *
 * Grows lazily to the high-water mark across passes; never shrinks. Single-threaded only.
 */
class ScratchPool<T : Any>(private val factory: () -> T) {
    private val instances = mutableListOf<T>()
    private var used = 0

    /** Returns a pooled instance. The caller is expected to overwrite its contents before use. */
    fun acquire(): T {
        val instance = if (used < instances.size) instances[used] else factory().also { instances.add(it) }
        used++
        return instance
    }

    /** Resets the internal cursor so the next [acquire] reuses instances from the start. */
    fun reset() { used = 0 }
}
