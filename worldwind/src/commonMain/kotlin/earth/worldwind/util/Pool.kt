package earth.worldwind.util

/**
 * Pool provides an interface for managing a pool of object instances.
 *
 * @param <T> the pooled type */
interface Pool<T> {
    /**
     * Acquires an instance from the pool. This returns null if the pool is empty.
     *
     * @return an instance from the pool, or null if the pool is empty
     */
    fun acquire(): T?

    /**
     * Releases an instance to the pool. This has no effect if the instance is null.
     *
     * @param instance the instance to release
     */
    fun release(instance: T?)
}