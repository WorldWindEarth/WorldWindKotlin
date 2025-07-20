package earth.worldwind.util

expect class SynchronizedPool<T>() : Pool<T> {
    override fun acquire(): T?
    override fun release(instance: T?)
}