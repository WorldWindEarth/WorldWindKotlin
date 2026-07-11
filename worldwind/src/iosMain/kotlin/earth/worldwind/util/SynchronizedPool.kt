package earth.worldwind.util

import platform.Foundation.NSLock

/** Kotlin/Native threads are real - decode workers and the render thread share these pools,
 *  so acquire/release must lock or two consumers can be handed the same pooled instance. */
actual class SynchronizedPool<T> : Pool<T> {
    private val lock = NSLock()
    private val pool = BasicPool<T>()

    actual override fun acquire(): T? {
        lock.lock()
        try {
            return pool.acquire()
        } finally {
            lock.unlock()
        }
    }

    actual override fun release(instance: T?) {
        lock.lock()
        try {
            pool.release(instance)
        } finally {
            lock.unlock()
        }
    }
}
