package earth.worldwind.util

/**
 * JS use BasicPool instead of SynchronizedPool
 */
actual class SynchronizedPool<T>: BasicPool<T>()