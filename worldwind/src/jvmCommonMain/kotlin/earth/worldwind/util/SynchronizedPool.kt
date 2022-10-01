package earth.worldwind.util

actual open class SynchronizedPool<T>: BasicPool<T>() {
    protected val lock: Any = Any()

    override fun acquire() = synchronized(lock) { super.acquire() }

    override fun release(instance: T?) = synchronized(lock) { super.release(instance) }
}